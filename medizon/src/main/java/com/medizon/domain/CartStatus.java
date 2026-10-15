package com.medizon.domain;

/** An account has at most one ACTIVE cart; checkout closes it, and the next medicine added opens a new one. */
public enum CartStatus { ACTIVE, CHECKED_OUT }
