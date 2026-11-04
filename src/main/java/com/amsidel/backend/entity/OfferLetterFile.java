/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The PDF of an issued offer letter, exactly as it was issued: a new brand logo or a change to how letters are laid out
 * does not change a letter already given. Kept apart from {@link OfferLetter} so that listing letters loads no files.
 */
@Entity
@Table(name = "offer_letter_files")
@Getter
@Setter
@NoArgsConstructor
public class OfferLetterFile {

    /** Same value as offer_letters.id. */
    @Id
    private Long offerLetterId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    public OfferLetterFile(Long offerLetterId, byte[] content) {
        this.offerLetterId = offerLetterId;
        this.content = content;
    }
}
