package org.xmlpull.v1;

/** Thrown to signal an XML pull parsing problem. */
public class XmlPullParserException extends Exception {
    private static final long serialVersionUID = 1L;

    protected Throwable detail;
    protected int row = -1;
    protected int column = -1;

    public XmlPullParserException(String message) {
        super(message);
    }

    public XmlPullParserException(String message, XmlPullParser parser, Throwable cause) {
        super(describe(message, parser, cause), cause);
        if (parser != null) {
            row = parser.getLineNumber();
            column = parser.getColumnNumber();
        }
        detail = cause;
    }

    private static String describe(String message, XmlPullParser parser, Throwable cause) {
        return (message == null ? "" : message + " ")
                + (parser == null ? "" : "(position:" + parser.getPositionDescription() + ") ")
                + (cause == null ? "" : "caused by: " + cause);
    }

    public Throwable getDetail() {
        return detail;
    }

    public int getLineNumber() {
        return row;
    }

    public int getColumnNumber() {
        return column;
    }
}
