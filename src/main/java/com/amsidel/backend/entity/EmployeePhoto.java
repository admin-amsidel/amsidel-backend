package com.amsidel.backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Profile photo uploaded from the portal, one per employee. Kept apart from {@link Employee} so listing
 * employees never loads image bytes.
 */
@Entity
@Table(name = "employee_photos")
@Getter
@Setter
@NoArgsConstructor
public class EmployeePhoto {

    /** Same value as employees.id. */
    @Id
    private Long employeeId;

    /** The web app sends a 512 x 512 JPEG of about 50-150 KB. bytea is spelled out: a large length would
     *  otherwise become an "oid" large object on PostgreSQL. */
    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    @Column(nullable = false, length = 30)
    private String contentType;

    @Column(nullable = false)
    private int sizeBytes;

    @Column(nullable = false)
    private Instant updatedAt;
}
