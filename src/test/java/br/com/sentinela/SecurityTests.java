package br.com.sentinela;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Integração com criptografia e arquivos reais. Todos os dados são fictícios. */
public final class SecurityTests {
    private static final String PASSWORD = "frase de teste publico 2026";
    private static final String NEW_PASSWORD = "outra frase de teste publico 2026";
    private static final String SECRET = "SEGREDO_FICTICIO_EXCLUSIVO_DO_TESTE_987654";
    private static final VaultService SERVICE = new VaultService();
    private static Path root;
    private static int passed, failed;
    @FunctionalInterface interface Task { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        root = Files.createTempDirectory("sentinela-tests-");
        try {
            run("cadastro, persistência e campos criptografados", SecurityTests::roundtrip);
            run("senha incorreta e liberação de trava", SecurityTests::wrongPassword);
            run("adulteração do texto cifrado rejeitada", SecurityTests::tamper);
            run("cabeçalho autenticado contra adulteração", SecurityTests::tamperHeader);
            run("nonce aleatório a cada gravação", SecurityTests::freshNonce);
            run("cofre existente não é sobrescrito", SecurityTests::existingFile);
            run("duas sessões concorrentes impedidas", SecurityTests::concurrentSession);
            run("backup criptografado pode ser aberto", SecurityTests::backup);
            run("backup existente não é sobrescrito", SecurityTests::backupNoOverwrite);
            run("troca de senha e compatibilidade dos backups antigos", SecurityTests::rotation);
            run("sessão expirada é recusada pelo serviço", SecurityTests::timeout);
            run("sessão encerrada não permite consultas", SecurityTests::closedSession);
            run("arrays de senha são limpos", SecurityTests::passwordWipe);
            run("alteração externa impede atualização silenciosa", SecurityTests::externalChange);
            run("edição e exclusão exigem nova autenticação", SecurityTests::editAndDelete);
            run("cinco confirmações incorretas bloqueiam a sessão", SecurityTests::reauthLimit);
            run("limites de senha e campos", SecurityTests::validation);
            run("arquivos truncados e parâmetros inválidos", SecurityTests::invalidFile);
            run("decodificador limita contagens e tamanho de campos", SecurityTests::boundedDecoder);
            run("Unicode e metadados do perfil preservados", SecurityTests::unicode);
            run("histórico registra somente tipos de alteração", SecurityTests::audit);
            run("espera progressiva de tentativas no processo", SecurityTests::throttle);
            run("links simbólicos não são usados como cofre", SecurityTests::symlink);
            System.out.println("\nResultado: " + passed + " testes passaram; " + failed + " falharam.");
            if (failed > 0) System.exit(1);
        } finally {
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static void run(String name, Task task) {
        try { task.run(); passed++; System.out.println("OK  " + name); }
        catch (Throwable error) { failed++; System.err.println("FALHA  " + name + " — " + error); }
    }
    private static char[] password() { return PASSWORD.toCharArray(); }
    private static Path file() { return root.resolve(UUID.randomUUID() + ".svault"); }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Condição de teste não satisfeita."); }
    private static void rejects(Task task) throws Exception {
        try { task.run(); } catch (VaultException expected) { return; }
        throw new AssertionError("A operação deveria ter sido recusada.");
    }
    private static UUID add(VaultService.Session session) throws Exception {
        return session.add("Registro fictício", "Credencial", "usuario-ficticio", SECRET, "nota privada fictícia");
    }
    private static void roundtrip() throws Exception {
        Path path = file(); UUID id;
        try (var s = SERVICE.create(path, "Perfil Fictício", password())) { id = add(s); }
        String disk = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
        check(!disk.contains(SECRET) && !disk.contains("usuario-ficticio") && !disk.contains(PASSWORD)
                && !disk.contains("Perfil Fict"));
        try (var s = SERVICE.open(path, password())) {
            check(s.entries().size() == 1); check(s.reveal(id, password()).secret().equals(SECRET));
        }
    }
    private static void wrongPassword() throws Exception {
        Path path = file(); try (var s = SERVICE.create(path, "Teste", password())) { }
        rejects(() -> SERVICE.open(path, "senha incorreta para o teste".toCharArray()));
        try (var s = SERVICE.open(path, password())) { check(s.isOpen()); }
    }
    private static void tamper() throws Exception {
        Path path = file(); try (var s = SERVICE.create(path, "Teste", password())) { add(s); }
        byte[] bytes = Files.readAllBytes(path); bytes[bytes.length - 1] ^= 1; Files.write(path, bytes);
        rejects(() -> SERVICE.open(path, password()));
    }
    private static void tamperHeader() throws Exception {
        Path path = file(); try (var s = SERVICE.create(path, "Teste", password())) { }
        byte[] bytes = Files.readAllBytes(path); bytes[20] ^= 1; Files.write(path, bytes);
        rejects(() -> SERVICE.open(path, password()));
    }
    private static void freshNonce() throws Exception {
        Path path = file();
        try (var s = SERVICE.create(path, "Teste", password())) {
            byte[] first = Crypto.parse(Files.readAllBytes(path)).nonce();
            add(s); byte[] next = Crypto.parse(Files.readAllBytes(path)).nonce();
            check(!Arrays.equals(first, next));
        }
    }
    private static void existingFile() throws Exception {
        Path path = file(); Files.writeString(path, "arquivo anterior fictício");
        byte[] before = Files.readAllBytes(path);
        rejects(() -> SERVICE.create(path, "Teste", password()));
        check(Arrays.equals(before, Files.readAllBytes(path)));
    }
    private static void concurrentSession() throws Exception {
        Path path = file(); try (var s = SERVICE.create(path, "Teste", password())) {
            rejects(() -> SERVICE.open(path, password())); check(s.isOpen());
        }
        try (var s = SERVICE.open(path, password())) { check(s.isOpen()); }
    }
    private static void backup() throws Exception {
        Path path = file(), backup = file();
        try (var s = SERVICE.create(path, "Teste", password())) {
            add(s); s.backup(backup, password()); check(Arrays.equals(Files.readAllBytes(path), Files.readAllBytes(backup)));
        }
        try (var s = SERVICE.open(backup, password())) { check(s.entries().size() == 1); }
    }
    private static void backupNoOverwrite() throws Exception {
        Path path = file(), backup = file(); Files.writeString(backup, "backup anterior fictício");
        byte[] before = Files.readAllBytes(backup);
        try (var s = SERVICE.create(path, "Teste", password())) {
            rejects(() -> s.backup(backup, password()));
            rejects(() -> s.backup(path, password()));
        }
        check(Arrays.equals(before, Files.readAllBytes(backup)));
    }
    private static void rotation() throws Exception {
        Path path = file(), backup = file(); UUID id;
        try (var s = SERVICE.create(path, "Teste", password())) {
            id = add(s); s.backup(backup, password());
            byte[] oldSalt = Crypto.parse(Files.readAllBytes(path)).salt();
            s.changePassword(password(), NEW_PASSWORD.toCharArray());
            check(!Arrays.equals(oldSalt, Crypto.parse(Files.readAllBytes(path)).salt()));
        }
        rejects(() -> SERVICE.open(path, password()));
        try (var s = SERVICE.open(path, NEW_PASSWORD.toCharArray())) {
            check(s.reveal(id, NEW_PASSWORD.toCharArray()).secret().equals(SECRET));
        }
        try (var s = SERVICE.open(backup, password())) { check(s.entries().size() == 1); }
    }
    private static void timeout() throws Exception {
        AtomicLong clock = new AtomicLong(0); VaultService service = new VaultService(clock::get);
        try (var s = service.create(file(), "Teste", password())) {
            clock.addAndGet(IdleGuard.TIMEOUT); check(s.expired()); rejects(s::entries); check(!s.isOpen());
        }
    }
    private static void closedSession() throws Exception {
        var s = SERVICE.create(file(), "Teste", password()); s.close();
        rejects(s::entries); rejects(() -> add(s)); rejects(() -> s.reveal(UUID.randomUUID(), password()));
    }
    private static void passwordWipe() throws Exception {
        Path path = file(); char[] chars = password();
        try (var s = SERVICE.create(path, "Teste", chars)) { }
        for (char c : chars) check(c == '\0');
        chars = "incorreta".toCharArray(); final char[] wrong = chars;
        rejects(() -> SERVICE.open(path, wrong)); for (char c : chars) check(c == '\0');
    }
    private static void externalChange() throws Exception {
        Path path = file();
        try (var s = SERVICE.create(path, "Teste", password())) {
            byte[] changed = Files.readAllBytes(path); changed[changed.length - 1] ^= 1; Files.write(path, changed);
            rejects(() -> add(s)); check(s.entries().isEmpty()); check(Arrays.equals(changed, Files.readAllBytes(path)));
        }
    }
    private static void editAndDelete() throws Exception {
        try (var s = SERVICE.create(file(), "Teste", password())) {
            UUID id = add(s);
            rejects(() -> s.update(id, "Novo título", "Outro", "conta", "novo segredo", "", "errada".toCharArray()));
            check(s.reveal(id, password()).secret().equals(SECRET));
            s.update(id, "Novo título", "Outro", "conta", "novo segredo", "", password());
            check(s.reveal(id, password()).secret().equals("novo segredo"));
            rejects(() -> s.delete(id, "errada".toCharArray())); check(s.entries().size() == 1);
            s.delete(id, password()); check(s.entries().isEmpty());
        }
    }
    private static void reauthLimit() throws Exception {
        try (var s = SERVICE.create(file(), "Teste", password())) {
            UUID id = add(s);
            for (int i = 0; i < 5; i++) rejects(() -> s.reveal(id, "errada".toCharArray()));
            check(!s.isOpen());
        }
    }
    private static void validation() throws Exception {
        rejects(() -> SERVICE.create(file(), "Teste", "curta".toCharArray()));
        rejects(() -> SERVICE.create(file(), "Teste", " ".repeat(16).toCharArray()));
        try (var s = SERVICE.create(file(), "Teste", password())) {
            rejects(() -> s.add("", "Outro", "", "segredo", ""));
            rejects(() -> s.add("Título", "Outro", "", "x".repeat(4097), ""));
            rejects(() -> s.changePassword(password(), "curta".toCharArray()));
            check(s.isOpen());
        }
    }
    private static void invalidFile() throws Exception {
        for (byte[] bytes : List.of(new byte[3], new byte[Crypto.HEADER_BYTES + 16])) {
            Path path = file(); Files.write(path, bytes); rejects(() -> SERVICE.open(path, password()));
        }
        Path path = file(); try (var s = SERVICE.create(path, "Teste", password())) { }
        byte[] bytes = Files.readAllBytes(path); ByteBuffer.wrap(bytes).putInt(12, Integer.MAX_VALUE); Files.write(path, bytes);
        rejects(() -> SERVICE.open(path, password()));
    }
    private static void boundedDecoder() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(1); out.writeByte('A'); out.writeInt(Integer.MAX_VALUE);
        rejects(() -> VaultData.decode(bytes.toByteArray()));
        rejects(() -> VaultData.decode(ByteBuffer.allocate(4).putInt(Integer.MAX_VALUE).array()));
    }
    private static void unicode() throws Exception {
        Path path = file(); UUID id;
        try (var s = SERVICE.create(path, "Perfil de teste — 中文", password())) {
            id = s.add("Título fictício", "Anotação", "exemplo", "texto fictício 🔒 日本語", "Notas com acentos");
        }
        try (var s = SERVICE.open(path, password())) {
            check(s.owner().equals("Perfil de teste — 中文"));
            check(s.reveal(id, password()).secret().equals("texto fictício 🔒 日本語"));
        }
    }
    private static void audit() throws Exception {
        try (var s = SERVICE.create(file(), "Teste", password())) {
            UUID id = add(s); s.delete(id, password());
            check(s.activity().size() == 3);
            check(s.activity().get(2).action().equals("ITEM_EXCLUIDO"));
            check(!s.activity().toString().contains(SECRET));
        }
    }
    private static void throttle() {
        AtomicLong clock = new AtomicLong(0); LoginThrottle gate = new LoginThrottle(clock::get); Path path = file();
        for (int i = 0; i < 4; i++) gate.failure(path); check(gate.secondsLeft(path) == 0);
        gate.failure(path); check(gate.secondsLeft(path) == 30);
        clock.addAndGet(30_000_000_000L); check(gate.secondsLeft(path) == 0);
        gate.failure(path); check(gate.secondsLeft(path) == 60);
        gate.success(path); check(gate.secondsLeft(path) == 0);
    }
    private static void symlink() throws Exception {
        Path target = file(), link = file();
        try (var s = SERVICE.create(target, "Teste", password())) { }
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | FileSystemException | SecurityException e) {
            // Windows sem privilégio para symlink: verifica a política pelo caminho de código; sem criar link.
            System.out.println("    Ambiente sem criação de symlink; caso de link não exercitado."); return;
        }
        rejects(() -> SERVICE.open(link, password()));
    }
}
