package com.amsidel.backend.dto;

/** Whether the server can send mail, and the address staff will see it from. */
public record MailSetup(boolean ready, String from, String fromName) {
}
