package app.bilincli.android;

import app.bilincli.core.Ledger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Defteri tek bir JSON dosyasında tutar; yazma atomiktir (geçici dosya + yeniden adlandırma). */
final class FileStorage implements Ledger.Storage {
    private final File file;

    FileStorage(File file) {
        this.file = file;
    }

    @Override
    public String read() {
        try {
            if (!file.exists()) return null;
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Veri dosyası okunamadı: " + e.getMessage(), e);
        }
    }

    @Override
    public void write(String data) {
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        } catch (IOException e) {
            throw new IllegalStateException("Veri dosyası yazılamadı: " + e.getMessage(), e);
        }
        if (!tmp.renameTo(file)) throw new IllegalStateException("Veri dosyası güncellenemedi");
    }

    void delete() {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
