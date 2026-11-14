/**
 * FTC-Sim as a user runs it: {@link dev.ftcsim.app.Main} puts the window on a {@link dev.ftcsim.app.RealHost}, which
 * starts the simulator as a worker process and prepares team projects with the project importer. This is the one
 * place that knows where things are on the computer: the folders under {@code %LOCALAPPDATA%\FTC-Sim}
 * ({@link dev.ftcsim.app.AppFolders}), the jars of the worker ({@link dev.ftcsim.app.AppClasspath}), and the JDK and
 * Android SDK that a project's own build needs. See {@code docs/architecture/app.md}.
 */
package dev.ftcsim.app;
