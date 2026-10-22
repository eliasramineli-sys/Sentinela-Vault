package br.com.sentinela;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.Set;

final class VaultFiles implements AutoCloseable {
    final Path path;
    private final FileChannel lockChannel;
    private final FileLock lock;

    private VaultFiles(Path path, FileChannel channel, FileLock lock) {
        this.path = path; this.lockChannel = channel; this.lock = lock;
    }

    static FileAttribute<?>[] permissions(Path directory) throws IOException {
        return Files.getFileStore(directory).supportsFileAttributeView("posix")
                ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))}
                : new FileAttribute<?>[0];
    }

    static VaultFiles acquire(Path supplied) throws VaultException {
        FileChannel channel = null;
        try {
            Path absolute = supplied.toAbsolutePath().normalize();
            Path parent = absolute.getParent().toRealPath();
            Path path = parent.resolve(absolute.getFileName());
            if (Files.isSymbolicLink(path)) throw new VaultException("Links simbólicos não são aceitos como cofre.");
            Path lockPath = parent.resolve("." + path.getFileName() + ".lck");
            channel = FileChannel.open(lockPath,
                    Set.of(StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS), permissions(parent));
            FileLock lock = channel.tryLock();
            if (lock == null) throw new VaultException("Este cofre já está aberto em outra sessão.");
            return new VaultFiles(path, channel, lock);
        } catch (IOException | OverlappingFileLockException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            throw new VaultException("Não foi possível acessar o cofre; verifique permissões ou outra sessão aberta.", e);
        } catch (VaultException e) {
            if (channel != null) try { channel.close(); } catch (IOException ignored) { }
            throw e;
        }
    }

    byte[] read() throws VaultException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (size < Crypto.HEADER_BYTES + 16 || size > Crypto.MAX_FILE_BYTES)
                throw new VaultException("Arquivo inválido ou maior que 8 MiB.");
            ByteBuffer buffer = ByteBuffer.allocate((int) size);
            while (buffer.hasRemaining()) if (channel.read(buffer) < 0)
                throw new VaultException("Arquivo truncado durante a leitura.");
            if (channel.size() != size) throw new VaultException("O arquivo mudou durante a leitura.");
            return buffer.array();
        } catch (IOException e) { throw new VaultException("Não foi possível ler o cofre.", e); }
    }

    void create(byte[] encrypted) throws VaultException {
        writeNew(path, encrypted);
    }

    static void writeNew(Path path, byte[] encrypted) throws VaultException {
        boolean created = false;
        try (FileChannel channel = FileChannel.open(path,
                Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS),
                permissions(path.toAbsolutePath().getParent()))) {
            created = true;
            writeAll(channel, encrypted);
        } catch (IOException e) {
            if (created) try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            throw new VaultException("Não foi possível criar o arquivo. Escolha um nome novo e verifique a pasta.", e);
        }
    }

    void replace(byte[] encrypted, byte[] expectedHash) throws VaultException {
        byte[] current = read();
        if (!MessageDigest.isEqual(Crypto.hash(current), expectedHash))
            throw new VaultException("O arquivo foi alterado fora desta sessão. Bloqueie e reabra antes de continuar.");
        Path temporary = null;
        try {
            temporary = Files.createTempFile(path.getParent(), ".sentinela-", ".tmp", permissions(path.getParent()));
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writeAll(channel, encrypted);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            throw new VaultException("A pasta não oferece substituição atômica. O cofre original foi preservado.", e);
        } catch (IOException e) { throw new VaultException("Falha ao salvar. O cofre não foi atualizado.", e); }
        finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { } }
    }

    private static void writeAll(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
        channel.force(true);
    }

    @Override public void close() {
        try { lock.release(); } catch (IOException ignored) { }
        try { lockChannel.close(); } catch (IOException ignored) { }
    }
}
