/**
 * How a viewer talks to a simulator: a request to open a run, the information and status that come back, and the
 * port both sides agree on. Plain data and one interface, so that the simulator can live in another process.
 * The {@link dev.ftcsim.contracts.session.Catalogue} says what a request may name, and
 * {@link dev.ftcsim.contracts.session.MatchStatus} reports a timed match, which the simulator keeps in simulated time.
 */
package dev.ftcsim.contracts.session;
