package com.amsidel.backend.entity;

/** OPEN tasks can be logged against; CLOSED ones keep their history but take no new hours. */
public enum TaskStatus {
    OPEN,
    CLOSED
}
