package br.com.sentinela;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.LongSupplier;

/** Regras do cofre. Os arrays de senha recebidos são consumidos e limpos. */
public final class VaultService {
    private final LongSupplier clock;
    public VaultService() { this(System::nanoTime); }
    VaultService(LongSupplier clock) { this.clock = clock; }

    public Session create(Path path, String owner, char[] password) throws VaultException {
        VaultFiles files = null;
        byte[] key = null, plain = null;
        boolean transferred = false;
        try {
            Crypto.validateNewPassword(password);
            owner = owner == null ? "" : owner.strip();
            VaultData.text(owner, 1, 60);
            files = VaultFiles.acquire(path);
            byte[] salt = Crypto.random(Crypto.SALT_BYTES);
            key = Crypto.derive(password, salt);
            VaultData data = new VaultData(owner, List.of(), List.of()).with(List.of(), "COFRE_CRIADO");
            plain = data.encode();
            byte[] encoded = Crypto.encrypt(plain, key, salt);
            files.create(encoded);
            Session session = new Session(files, key, salt, data, Crypto.hash(encoded), clock);
            transferred = true;
            return session;
        } catch (IllegalArgumentException e) {
            throw new VaultException("Informe um nome de perfil entre 1 e 60 caracteres.", e);
        } finally {
            Crypto.wipe(password); Crypto.wipe(plain);
            if (!transferred) { Crypto.wipe(key); if (files != null) files.close(); }
        }
    }

    public Session open(Path path, char[] password) throws VaultException {
        VaultFiles files = null;
        byte[] key = null, plain = null;
        boolean transferred = false;
        try {
            files = VaultFiles.acquire(path);
            byte[] encoded = files.read();
            Crypto.Envelope envelope = Crypto.parse(encoded);
            key = Crypto.derive(password, envelope.salt());
            plain = Crypto.decrypt(envelope, key);
            VaultData data = VaultData.decode(plain);
            Session session = new Session(files, key, envelope.salt(), data, Crypto.hash(encoded), clock);
            transferred = true;
            return session;
        } finally {
            Crypto.wipe(password); Crypto.wipe(plain);
            if (!transferred) { Crypto.wipe(key); if (files != null) files.close(); }
        }
    }

    public record EntrySummary(UUID id, String title, String category, String account, long updatedAt) {}

    public static final class Session implements AutoCloseable {
        private final VaultFiles files;
        private final IdleGuard idle;
        private byte[] key, salt, fingerprint;
        private VaultData data;
        private int reauthFailures;
        private boolean closed;

        private Session(VaultFiles files, byte[] key, byte[] salt, VaultData data,
                        byte[] fingerprint, LongSupplier clock) {
            this.files = files; this.key = key; this.salt = salt;
            this.data = data; this.fingerprint = fingerprint; idle = new IdleGuard(clock);
        }

        public synchronized boolean isOpen() { return !closed; }
        public synchronized boolean expired() { return closed || idle.expired(); }
        public synchronized long secondsLeft() { return closed ? 0 : idle.secondsLeft(); }
        public synchronized Path path() { return files.path; }

        private void check() throws VaultException {
            if (closed) throw new VaultException("A sessão está bloqueada. Abra o cofre novamente.");
            if (idle.expired()) {
                close(); throw new VaultException("Sessão bloqueada por inatividade.");
            }
        }

        public synchronized void touch() throws VaultException { check(); idle.touch(); }
        public synchronized String owner() throws VaultException { check(); return data.owner; }

        public synchronized List<EntrySummary> entries() throws VaultException {
            check();
            return data.entries.stream().map(e -> new EntrySummary(e.id(), e.title(), e.category(),
                    e.account(), e.updatedAt())).toList();
        }

        public synchronized List<VaultData.Event> activity() throws VaultException {
            check(); return List.copyOf(data.events);
        }

        private void verify(char[] password) throws VaultException {
            byte[] candidate = null;
            try {
                check();
                candidate = Crypto.derive(password, salt);
                if (!MessageDigest.isEqual(candidate, key)) {
                    if (++reauthFailures >= 5) {
                        close(); throw new VaultException("Sessão bloqueada após cinco confirmações incorretas.");
                    }
                    throw new VaultException("Senha mestre incorreta.");
                }
                reauthFailures = 0; idle.touch();
            } finally { Crypto.wipe(password); Crypto.wipe(candidate); }
        }

        public synchronized VaultData.Entry reveal(UUID id, char[] password) throws VaultException {
            verify(password); return find(id);
        }

        private VaultData.Entry find(UUID id) throws VaultException {
            return data.entries.stream().filter(e -> e.id().equals(id)).findFirst()
                    .orElseThrow(() -> new VaultException("Selecione um registro existente."));
        }

        public synchronized UUID add(String title, String category, String account,
                                     String secret, String notes) throws VaultException {
            check();
            if (data.entries.size() >= VaultData.MAX_ENTRIES)
                throw new VaultException("O limite é de 1.000 registros por cofre.");
            long now = Instant.now().toEpochMilli();
            try {
                VaultData.Entry entry = new VaultData.Entry(UUID.randomUUID(), title.strip(), category.strip(),
                        account, secret, notes, now, now);
                List<VaultData.Entry> entries = new ArrayList<>(data.entries); entries.add(entry);
                commit(data.with(entries, "ITEM_CRIADO"), key, salt);
                return entry.id();
            } catch (IllegalArgumentException e) { throw new VaultException("Preencha título, categoria e informação protegida; confira os limites dos campos.", e); }
        }

        public synchronized void update(UUID id, String title, String category, String account,
                                        String secret, String notes, char[] password) throws VaultException {
            verify(password);
            VaultData.Entry original = find(id);
            try {
                VaultData.Entry updated = new VaultData.Entry(id, title.strip(), category.strip(), account,
                        secret, notes, original.createdAt(), Math.max(original.createdAt(), Instant.now().toEpochMilli()));
                List<VaultData.Entry> entries = new ArrayList<>(data.entries);
                entries.replaceAll(e -> e.id().equals(id) ? updated : e);
                commit(data.with(entries, "ITEM_EDITADO"), key, salt);
            } catch (IllegalArgumentException e) { throw new VaultException("Um dos campos é inválido ou excedeu seu limite.", e); }
        }

        public synchronized void delete(UUID id, char[] password) throws VaultException {
            verify(password); find(id);
            List<VaultData.Entry> entries = new ArrayList<>(data.entries);
            entries.removeIf(e -> e.id().equals(id));
            commit(data.with(entries, "ITEM_EXCLUIDO"), key, salt);
        }

        public synchronized void changePassword(char[] current, char[] replacement) throws VaultException {
            byte[] newKey = null;
            try {
                verify(current); Crypto.validateNewPassword(replacement);
                byte[] newSalt = Crypto.random(Crypto.SALT_BYTES);
                newKey = Crypto.derive(replacement, newSalt);
                commit(data.with(data.entries, "SENHA_ALTERADA"), newKey, newSalt);
                Crypto.wipe(key); Crypto.wipe(salt);
                key = newKey; salt = newSalt; newKey = null;
            } finally { Crypto.wipe(current); Crypto.wipe(replacement); Crypto.wipe(newKey); }
        }

        public synchronized void backup(Path target, char[] password) throws VaultException {
            verify(password);
            byte[] current = files.read();
            if (!MessageDigest.isEqual(Crypto.hash(current), fingerprint))
                throw new VaultException("O cofre foi alterado fora desta sessão. Bloqueie e reabra antes de copiar.");
            VaultFiles.writeNew(target.toAbsolutePath().normalize(), current);
        }

        private void commit(VaultData next, byte[] withKey, byte[] withSalt) throws VaultException {
            byte[] plain = next.encode();
            try {
                byte[] encoded = Crypto.encrypt(plain, withKey, withSalt);
                files.replace(encoded, fingerprint);
                fingerprint = Crypto.hash(encoded); data = next; idle.touch();
            } finally { Crypto.wipe(plain); }
        }

        @Override public synchronized void close() {
            if (!closed) {
                closed = true; Crypto.wipe(key); Crypto.wipe(salt); Crypto.wipe(fingerprint);
                key = null; salt = null; fingerprint = null; data = null; files.close();
            }
        }
    }
}
