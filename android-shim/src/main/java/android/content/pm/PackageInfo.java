package android.content.pm;

/** Overall information about an installed package. The simulator has one: the robot controller app. */
public class PackageInfo {
    public String packageName;
    public String versionName;
    public int versionCode;
    public long firstInstallTime;
    public long lastUpdateTime;
}
