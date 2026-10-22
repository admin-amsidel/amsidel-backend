package com.amsidel.backend.service;

import com.amsidel.backend.dto.EmployeeResponse;
import com.amsidel.backend.entity.Employee;
import com.amsidel.backend.entity.EmployeePhoto;
import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.EmployeePhotoRepository;
import com.amsidel.backend.repository.EmployeeRepository;
import com.amsidel.backend.security.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;

/**
 * Profile photos uploaded from the portal. Everyone changes their own; owners and admins change the photos
 * of people they manage (same rules as editing the record).
 */
@Service
@RequiredArgsConstructor
public class PhotoService {

    /** The web app shrinks photos to about 100 KB; this only stops oversized or wrong files. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    private final EmployeeRepository employeeRepository;
    private final EmployeePhotoRepository photoRepository;
    private final EmployeeService employeeService;

    @Transactional(readOnly = true)
    public EmployeePhoto myPhoto(AuthUser me) {
        return load(me.id());
    }

    @Transactional(readOnly = true)
    public EmployeePhoto photoOf(AuthUser me, Long employeeId) {
        employeeService.findVisible(me, employeeId);
        return load(employeeId);
    }

    @Transactional
    public EmployeeResponse uploadMine(AuthUser me, MultipartFile file) {
        return save(self(me), file);
    }

    @Transactional
    public EmployeeResponse uploadFor(AuthUser me, Long employeeId, MultipartFile file) {
        return save(employeeService.findManageable(me, employeeId), file);
    }

    @Transactional
    public EmployeeResponse removeMine(AuthUser me) {
        return remove(self(me));
    }

    @Transactional
    public EmployeeResponse removeFor(AuthUser me, Long employeeId) {
        return remove(employeeService.findManageable(me, employeeId));
    }

    private EmployeeResponse save(Employee employee, MultipartFile file) {
        byte[] bytes = read(file);
        String type = detectType(bytes);
        if (type == null) {
            throw ApiException.badRequest("Photo must be a JPEG, PNG or WebP image");
        }

        Instant now = Instant.now();
        EmployeePhoto photo = photoRepository.findById(employee.getId()).orElseGet(EmployeePhoto::new);
        photo.setEmployeeId(employee.getId());
        photo.setContent(bytes);
        photo.setContentType(type);
        photo.setSizeBytes(bytes.length);
        photo.setUpdatedAt(now);
        photoRepository.save(photo);

        employee.setPhotoUpdatedAt(now);
        return EmployeeResponse.from(employee);
    }

    private EmployeeResponse remove(Employee employee) {
        photoRepository.deleteById(employee.getId());
        employee.setPhotoUpdatedAt(null);
        return EmployeeResponse.from(employee);
    }

    private static byte[] read(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a photo to upload");
        }
        if (file.getSize() > MAX_BYTES) {
            throw ApiException.badRequest("Photo must be 2 MB or smaller");
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw ApiException.badRequest("The photo could not be read; please try again");
        }
    }

    /** Judged by the file's first bytes, not by its name or the type the browser claims. */
    static String detectType(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private Employee self(AuthUser me) {
        return employeeRepository.findWithBrandById(me.id())
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found: " + me.id()));
    }

    private EmployeePhoto load(Long employeeId) {
        return photoRepository.findById(employeeId)
                .orElseThrow(() -> new ResourceNotFoundException("No photo for employee " + employeeId));
    }
}
