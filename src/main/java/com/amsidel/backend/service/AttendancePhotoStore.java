/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.exception.ApiException;
import com.amsidel.backend.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Iterator;

/**
 * Check-in photos on disk, under app.attendance.photo-dir, at attendance/{yyyy}/{MM}/{employeeId}/{dd}.jpg
 * ({dd}-2.jpg, {dd}-3.jpg... for more field visits the same day).
 * Every photo is decoded and written again as a plain JPEG, which drops all EXIF metadata (camera, time,
 * GPS). Photos are only ever served through the authenticated API, never as static files.
 */
@Slf4j
@Component
public class AttendancePhotoStore {

    /** Upload limit, before re-encoding. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    /** Refuse images that would take too much memory to open. */
    private static final int MAX_PIXELS_SIDE = 8000;
    /** The app sends at most 1280 px; anything larger is shrunk. */
    private static final int MAX_SIDE = 1600;
    private static final float QUALITY = 0.8f;

    private final Path root;

    public AttendancePhotoStore(@Value("${app.attendance.photo-dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    /** The day's first entry is {dd}.jpg; more field visits that day are {dd}-2.jpg, {dd}-3.jpg... */
    public static String pathFor(Long employeeId, LocalDate day, int entryNo) {
        String name = entryNo <= 1 ? "%02d".formatted(day.getDayOfMonth()) : "%02d-%d".formatted(day.getDayOfMonth(), entryNo);
        return "attendance/%04d/%02d/%d/%s.jpg".formatted(day.getYear(), day.getMonthValue(), employeeId, name);
    }

    /**
     * The uploaded photo as a clean JPEG (no metadata), or a 400 when it is not a real JPEG/PNG photo.
     * The type is judged by the file's first bytes, not its name or the type the browser claims.
     */
    public static byte[] clean(byte[] upload) {
        if (upload == null || upload.length == 0) {
            throw ApiException.badRequest("Take a photo to check in");
        }
        if (upload.length > MAX_BYTES) {
            throw ApiException.badRequest("The photo must be 2 MB or smaller");
        }
        if (!isJpeg(upload) && !isPng(upload)) {
            throw ApiException.badRequest("The photo must be a JPEG or PNG image");
        }
        BufferedImage image = decode(upload);
        BufferedImage rgb = toRgb(image);
        return encodeJpeg(rgb);
    }

    public void save(String relative, byte[] jpeg) {
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), ".upload-", ".tmp");
            Files.write(temp, jpeg);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save attendance photo " + relative, e);
        }
    }

    public byte[] read(String relative) {
        try {
            return Files.readAllBytes(resolve(relative));
        } catch (NoSuchFileException e) {
            throw new ResourceNotFoundException("The photo for this day is missing");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read attendance photo " + relative, e);
        }
    }

    public void deleteQuietly(String relative) {
        try {
            Files.deleteIfExists(resolve(relative));
        } catch (IOException e) {
            log.warn("Could not delete attendance photo {}: {}", relative, e.getMessage());
        }
    }

    /** Inside the photo folder only (a stored path can never point elsewhere). */
    private Path resolve(String relative) {
        Path path = root.resolve(relative).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Photo path outside the photo folder: " + relative);
        }
        return path;
    }

    static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && b[4] == 0x0D && b[5] == 0x0A && b[6] == 0x1A && b[7] == 0x0A;
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw ApiException.badRequest("The photo could not be read; please take it again");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // ignore metadata while reading
                if (reader.getWidth(0) > MAX_PIXELS_SIDE || reader.getHeight(0) > MAX_PIXELS_SIDE) {
                    throw ApiException.badRequest("The photo is too large; please take it again");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw ApiException.badRequest("The photo could not be read; please take it again");
        }
    }

    /** Plain RGB (PNG transparency becomes white), at most MAX_SIDE on the longest side. */
    private static BufferedImage toRgb(BufferedImage image) {
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(image.getWidth(), image.getHeight()));
        int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(image, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** A JPEG with no metadata at all (nothing from the original file is copied). */
    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not encode attendance photo", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
