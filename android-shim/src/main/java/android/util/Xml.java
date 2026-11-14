package android.util;

import android.Unsupported;
import org.xmlpull.v1.XmlSerializer;

/** Entry points for XML parsing and writing. Only creating a serializer is provided, and the serializer does nothing yet. */
public final class Xml {
    private Xml() {}

    /**
     * The robot controller's configuration writer creates its serializer when the app starts, long before any
     * configuration is saved. Writing configuration files is not provided, so every member of the serializer
     * fails and names itself.
     */
    public static XmlSerializer newSerializer() {
        return Unsupported.object(XmlSerializer.class);
    }
}
