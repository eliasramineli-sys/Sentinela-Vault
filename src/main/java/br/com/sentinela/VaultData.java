package br.com.sentinela;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

final class VaultData {
    static final int MAX_ENTRIES = 1000, MAX_EVENTS = 100;
    final String owner;
    final List<Entry> entries;
    final List<Event> events;

    record Entry(UUID id, String title, String category, String account, String secret,
                 String notes, long createdAt, long updatedAt) {
        Entry {
            Objects.requireNonNull(id);
            text(title, 1, 120); text(category, 1, 40); text(account, 0, 160);
            text(secret, 1, 4096); text(notes, 0, 8192);
            if (createdAt < 0 || updatedAt < createdAt) throw new IllegalArgumentException("Datas inválidas.");
        }
    }

    record Event(long timestamp, String action) {
        Event {
            if (timestamp < 0 || !Set.of("COFRE_CRIADO", "ITEM_CRIADO", "ITEM_EDITADO",
                    "ITEM_EXCLUIDO", "SENHA_ALTERADA").contains(action))
                throw new IllegalArgumentException("Evento inválido.");
        }
    }

    VaultData(String owner, List<Entry> entries, List<Event> events) {
        text(owner, 1, 60);
        if (entries.size() > MAX_ENTRIES || events.size() > MAX_EVENTS)
            throw new IllegalArgumentException("Limite de registros excedido.");
        Set<UUID> ids = new HashSet<>();
        for (Entry entry : entries) if (!ids.add(entry.id()))
            throw new IllegalArgumentException("Identificador duplicado.");
        this.owner = owner; this.entries = List.copyOf(entries); this.events = List.copyOf(events);
    }

    VaultData with(List<Entry> newEntries, String action) {
        List<Event> audit = new ArrayList<>(events);
        audit.add(new Event(Instant.now().toEpochMilli(), action));
        if (audit.size() > MAX_EVENTS) audit.remove(0);
        return new VaultData(owner, newEntries, audit);
    }

    static void text(String value, int min, int max) {
        if (value == null || value.length() < min || value.length() > max
                || (min > 0 && value.isBlank())) throw new IllegalArgumentException("Campo inválido ou muito longo.");
    }

    byte[] encode() throws VaultException {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, owner); out.writeInt(entries.size());
            for (Entry e : entries) {
                out.writeLong(e.id().getMostSignificantBits()); out.writeLong(e.id().getLeastSignificantBits());
                write(out, e.title()); write(out, e.category()); write(out, e.account());
                write(out, e.secret()); write(out, e.notes()); out.writeLong(e.createdAt()); out.writeLong(e.updatedAt());
            }
            out.writeInt(events.size());
            for (Event e : events) { out.writeLong(e.timestamp()); write(out, e.action()); }
            out.flush(); return bytes.toByteArray();
        } catch (IOException e) { throw new VaultException("Não foi possível preparar o cofre.", e); }
    }

    static VaultData decode(byte[] bytes) throws VaultException {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            String owner = read(in, 60);
            int count = count(in, MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) entries.add(new Entry(new UUID(in.readLong(), in.readLong()),
                    read(in, 120), read(in, 40), read(in, 160), read(in, 4096), read(in, 8192),
                    in.readLong(), in.readLong()));
            count = count(in, MAX_EVENTS);
            List<Event> events = new ArrayList<>();
            for (int i = 0; i < count; i++) events.add(new Event(in.readLong(), read(in, 40)));
            if (in.available() != 0) throw new IOException("Conteúdo excedente.");
            return new VaultData(owner, entries, events);
        } catch (IOException | IllegalArgumentException e) {
            throw new VaultException("Conteúdo interno do cofre inválido.", e);
        }
    }

    private static int count(DataInputStream in, int max) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > max) throw new IOException("Contagem inválida.");
        return n;
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        try { out.writeInt(bytes.length); out.write(bytes); }
        finally { Crypto.wipe(bytes); }
    }

    private static String read(DataInputStream in, int maxChars) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > maxChars * 4 || length > in.available())
            throw new IOException("Campo de tamanho inválido.");
        byte[] bytes = in.readNBytes(length);
        try {
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.length() > maxChars) throw new IOException("Campo muito longo.");
            return value;
        } catch (CharacterCodingException e) { throw new IOException("UTF-8 inválido.", e); }
        finally { Crypto.wipe(bytes); }
    }
}
