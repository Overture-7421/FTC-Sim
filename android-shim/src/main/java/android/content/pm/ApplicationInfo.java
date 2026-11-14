package android.content.pm;

/** Information about an installed application. The simulator has exactly one: the robot controller app. */
public class ApplicationInfo {
    public String packageName;
    public String sourceDir;
    public String[] splitSourceDirs;
}
