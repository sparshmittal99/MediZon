package com.medizon.domain;

/**
 * Where an order came from: the customer checking out, the auto-refill engine, or an approved exchange (a free
 * replacement that starts out confirmed).
 */
public enum OrderSource { CUSTOMER, AUTO_REORDER, EXCHANGE }
