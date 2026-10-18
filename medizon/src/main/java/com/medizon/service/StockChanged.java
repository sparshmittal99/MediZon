package com.medizon.service;

/** Published when a store's stock of a product may have gone up, so waiting "Notify me" alerts can be checked. */
public record StockChanged(Long medicineId) {}
