package dev.ftcsim.game.decode;

/**
 * What a solid is made of, as far as a ball can tell: how much it grips and how much it gives back. The engine takes the
 * square root of the two frictions' product and the larger of the two restitutions, so an ARTIFACT never bounces less
 * than its own restitution.
 */
record Material(float friction, float restitution) {

    /** The engine's own value for a field solid: the soft foam tiles of the floor and the perimeter. */
    static final Material DEFAULT = new Material(0.8f, 0.0f);

    /** The goal's polypropylene sheet and aluminium rails, which are slippery against a polypropylene ball. */
    static final Material PLASTIC = new Material(0.25f, 0.0f);
}
