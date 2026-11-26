package android.content.res;

import org.xmlpull.v1.XmlPullParser;

/**
 * The XML parsing interface returned by {@link Resources#getXml(int)}. On a device it reads compiled binary XML
 * resources; on the desktop simulator no XML robot configurations are shipped, so parsers returned by
 * {@link Resources#getXml(int)} stand at {@link XmlPullParser#END_DOCUMENT}.
 */
public interface XmlResourceParser extends XmlPullParser, AutoCloseable {
    @Override
    void close();
}
