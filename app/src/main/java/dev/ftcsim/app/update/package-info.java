/**
 * How a release of FTC-Sim keeps itself up to date: it asks GitHub for the latest release, offers a newer one in the window
 * and, when the person says so, fetches the installer of that release, checks it against the release's published SHA-256 and
 * runs it once the window has closed. {@link dev.ftcsim.app.update.GitHubUpdater} is the {@code UpdateService} the window
 * sees; {@link dev.ftcsim.app.update.Version} orders versions and {@link dev.ftcsim.app.update.AppVersion} says which one this
 * is. Development builds have no version and never look. See {@code docs/architecture/updates.md}.
 */
package dev.ftcsim.app.update;
