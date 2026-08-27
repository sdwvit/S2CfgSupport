package com.sdwvit.s2cfg

import com.intellij.openapi.diagnostic.Logger

/**
 * Everything here is cheap enough to leave on: the plugin's failure mode is a frozen UI, and a
 * freeze leaves no stack in the log unless the slow operation reports itself. Set
 * `#com.sdwvit.s2cfg:trace` in Help | Diagnostic Tools | Debug Log Settings for the full stream.
 */
object S2CfgLog {
  val LOG: Logger = Logger.getInstance("com.sdwvit.s2cfg")

  /** Warn when [what] takes longer than [thresholdMs] — the breadcrumb a hang report needs. */
  inline fun <T> timed(thresholdMs: Long = 200, what: () -> String, body: () -> T): T {
    if (!LOG.isTraceEnabled && thresholdMs <= 0) return body()
    val started = System.nanoTime()
    try {
      return body()
    } finally {
      val tookMs = (System.nanoTime() - started) / 1_000_000
      if (tookMs >= thresholdMs) LOG.warn("slow (${tookMs}ms): ${what()}")
      else if (LOG.isTraceEnabled) LOG.trace("${tookMs}ms: ${what()}")
    }
  }
}
