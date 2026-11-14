/**
 * The seam between the window and the application around it: {@link dev.ftcsim.ui.host.AppHost} gives the window a
 * link to a simulator and a {@link dev.ftcsim.ui.host.ProgramPreparer}. Both are declared here, in the window's module,
 * and implemented by the application, so the window depends on neither a worker process nor a build tool.
 */
package dev.ftcsim.ui.host;
