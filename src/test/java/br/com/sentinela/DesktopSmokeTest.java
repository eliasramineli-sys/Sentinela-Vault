package br.com.sentinela;

import java.awt.*;
import java.awt.event.WindowEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Teste opcional de interface em sessão gráfica. Usa apenas dados fictícios. */
public final class DesktopSmokeTest {
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("O teste exige uma sessão gráfica.");
        Path temporary = Files.createTempDirectory("sentinela-ui-");
        Path vault = temporary.resolve("teste-ficticio.svault");
        AtomicReference<Main> frame = new AtomicReference<>();
        try {
            try (var session = new VaultService().create(vault, "Perfil fictício", "frase de teste publico 2026".toCharArray())) {
                session.add("Documento fictício", "Documento", "identificador-ficticio", "segredo fictício", "Somente teste");
            }
            Main.main(new String[0]);
            for (int i = 0; i < 100 && frame.get() == null; i++) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Frame window : Frame.getFrames()) if (window instanceof Main main) frame.set(main);
                });
                Thread.sleep(50);
            }
            Main main = frame.get(); if (main == null) throw new AssertionError("Janela não abriu.");
            if (args.length > 0) screenshot(main, Path.of(args[0], "login.png"));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    field("loginPath").set(main, vault);
                    ((JTextField) field("selectedFile").get(main)).setText(vault.getFileName().toString());
                    ((JPasswordField) field("loginPassword").get(main)).setText("frase de teste publico 2026");
                    Method unlock = Main.class.getDeclaredMethod("unlock"); unlock.setAccessible(true); unlock.invoke(main);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            boolean opened = false;
            for (int i = 0; i < 200; i++) {
                AtomicReference<VaultService.Session> current = new AtomicReference<>();
                SwingUtilities.invokeAndWait(() -> {
                    try { current.set((VaultService.Session) field("session").get(main)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                if (current.get() != null) { opened = true; break; }
                Thread.sleep(50);
            }
            if (!opened) throw new AssertionError("Acesso pela interface não foi concluído.");
            if (args.length > 0) screenshot(main, Path.of(args[0], "cofre.png"));
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JTable table = (JTable) field("table").get(main);
                    if (table.getRowCount() != 1 || table.getColumnCount() != 4) throw new AssertionError("Tabela inesperada.");
                    if (!table.getValueAt(0, 2).toString().startsWith("••••")) throw new AssertionError("Identificador exposto.");
                    main.dispatchEvent(new WindowEvent(main, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.ICONIFIED));
                    if (field("session").get(main) != null) throw new AssertionError("Minimizar não bloqueou.");
                    if (table.getRowCount() != 0) throw new AssertionError("Tabela não foi limpa ao bloquear.");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            System.out.println("OK  interface: login, tabela com identificador mascarado e bloqueio ao minimizar.");
        } finally {
            if (frame.get() != null) SwingUtilities.invokeAndWait(() -> frame.get().dispatchEvent(new WindowEvent(frame.get(), WindowEvent.WINDOW_CLOSING)));
            try (var files = Files.walk(temporary)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static Field field(String name) throws Exception {
        Field field = Main.class.getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static void screenshot(Main main, Path output) throws Exception {
        Thread.sleep(200);
        AtomicReference<Rectangle> bounds = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> bounds.set(main.getBounds()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        ImageIO.write(new Robot().createScreenCapture(bounds.get()), "png", output.toFile());
    }
}
