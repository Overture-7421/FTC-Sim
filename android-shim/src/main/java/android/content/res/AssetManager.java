package android.content.res;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Access to the application's raw asset files. On a device they live inside the APK; here they are class path
 * resources below {@code assets/}, laid out as directories by the same build step that writes the resource table.
 */
public final class AssetManager {
    private static final String ASSETS = "assets";

    public InputStream open(String fileName) throws IOException {
        InputStream stream = AssetManager.class.getClassLoader().getResourceAsStream(ASSETS + '/' + fileName);
        if (stream == null) {
            throw new FileNotFoundException(fileName);
        }
        return stream;
    }

    /**
     * The names directly below a directory of the assets, pooled from every class path directory that has it;
     * empty when there is none, as on a device.
     */
    public String[] list(String path) throws IOException {
        String directory = path.isEmpty() ? ASSETS : ASSETS + '/' + path;
        Set<String> names = new TreeSet<>();
        Enumeration<URL> found = AssetManager.class.getClassLoader().getResources(directory);
        while (found.hasMoreElements()) {
            URL location = found.nextElement();
            if (!"file".equals(location.getProtocol())) {
                continue;
            }
            Path root;
            try {
                root = Path.of(location.toURI());
            } catch (URISyntaxException e) {
                throw new IOException("Not a directory of assets: " + location, e);
            }
            if (Files.isDirectory(root)) {
                try (Stream<Path> children = Files.list(root)) {
                    children.forEach(child -> names.add(child.getFileName().toString()));
                }
            }
        }
        return names.toArray(String[]::new);
    }
}
