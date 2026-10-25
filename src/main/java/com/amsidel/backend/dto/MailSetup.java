package com.amsidel.backend.dto;

/**
 * Whether the server can send mail, the address mail comes from, and the office address copied (CC) on every mail
 * (empty when none is set).
 */
public record MailSetup(boolean ready, String from, String fromName, String alwaysCc) {
}
