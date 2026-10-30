package com.investary.intelligence.domain;

/**
 * How research evidence relates to one holding. These are research categories, not trading signals.
 */
public enum ImpactCategory {
    /** Research specifically about this holding exists; no risk or opportunity reading is claimed. */
    DIRECT_HOLDING,
    /** Only general market or macro research is relevant to this holding. */
    MARKET_CONTEXT,
    /** Cited research describes a risk that may affect this holding. */
    POTENTIAL_RISK,
    /** Cited research describes a development that may benefit this holding. */
    POTENTIAL_OPPORTUNITY,
    /** The holding is mentioned in passing; worth watching, nothing specific to assess. */
    MONITORING_ONLY,
    /** The knowledge base has no usable evidence about this holding. */
    INSUFFICIENT_EVIDENCE
}
