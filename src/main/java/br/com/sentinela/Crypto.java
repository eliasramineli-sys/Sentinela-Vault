package br.com.sentinela;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

final class Crypto {
    static final int ITERATIONS = 600_000;
    static final int SALT_BYTES = 32, NONCE_BYTES = 12, TAG_BITS = 128;
    static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
    static final int HEADER_BYTES = 64;
    private static final byte[] MAGIC = {'S','E','N','T','V','A','0','1'};
    private static final SecureRandom RANDOM = new SecureRandom();

    record Envelope(byte[] salt, byte[] nonce, byte[] header, byte[] ciphertext) {}

    static byte[] random(int size) {
        byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return bytes;
    }

    static void validateNewPassword(char[] password) throws VaultException {
        if (password == null || password.length < 16 || password.length > 256)
            throw new VaultException("Use uma frase-senha entre 16 e 256 caracteres.");
        boolean nonSpace = false;
        for (char c : password) nonSpace |= !Character.isWhitespace(c);
        if (!nonSpace) throw new VaultException("A senha não pode conter apenas espaços.");
    }

    static byte[] derive(char[] password, byte[] salt) throws VaultException {
        if (password == null || password.length == 0 || password.length > 256)
            throw new VaultException("Senha mestre inválida.");
        PBEKeySpec spec = new PBEKeySpec(password, salt, ITERATIONS, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new VaultException("O ambiente Java não oferece os recursos criptográficos necessários.", e);
        } finally { spec.clearPassword(); }
    }

    static byte[] encrypt(byte[] plaintext, byte[] key, byte[] salt) throws VaultException {
        if (plaintext.length > MAX_FILE_BYTES - HEADER_BYTES - 16)
            throw new VaultException("O cofre excedeu o limite de 8 MiB.");
        byte[] nonce = random(NONCE_BYTES);
        byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).putInt(1)
                .putInt(ITERATIONS).put(salt).put(nonce).putInt(plaintext.length + 16).array();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(header);
            byte[] encrypted = cipher.doFinal(plaintext);
            return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
        } catch (GeneralSecurityException e) {
            throw new VaultException("Não foi possível criptografar o cofre.", e);
        }
    }

    static Envelope parse(byte[] file) throws VaultException {
        if (file.length < HEADER_BYTES + 16 || file.length > MAX_FILE_BYTES)
            throw new VaultException("Arquivo de cofre inválido ou maior que 8 MiB.");
        ByteBuffer input = ByteBuffer.wrap(file);
        byte[] magic = new byte[8]; input.get(magic);
        if (!Arrays.equals(magic, MAGIC) || input.getInt() != 1 || input.getInt() != ITERATIONS)
            throw new VaultException("Formato, versão ou parâmetros do cofre não suportados.");
        byte[] salt = new byte[SALT_BYTES], nonce = new byte[NONCE_BYTES];
        input.get(salt); input.get(nonce);
        if (input.getInt() != file.length - HEADER_BYTES)
            throw new VaultException("Tamanho do cofre inconsistente.");
        return new Envelope(salt, nonce, Arrays.copyOf(file, HEADER_BYTES),
                Arrays.copyOfRange(file, HEADER_BYTES, file.length));
    }

    static byte[] decrypt(Envelope envelope, byte[] key) throws VaultException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, envelope.nonce()));
            cipher.updateAAD(envelope.header());
            return cipher.doFinal(envelope.ciphertext());
        } catch (AEADBadTagException e) {
            throw new VaultException("Senha incorreta ou arquivo alterado/corrompido.");
        } catch (GeneralSecurityException e) {
            throw new VaultException("Não foi possível abrir o cofre.", e);
        }
    }

    static byte[] hash(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static void wipe(byte[] value) { if (value != null) Arrays.fill(value, (byte) 0); }
    static void wipe(char[] value) { if (value != null) Arrays.fill(value, '\0'); }
}
