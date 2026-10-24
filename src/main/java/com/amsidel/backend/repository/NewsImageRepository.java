package com.amsidel.backend.repository;

import com.amsidel.backend.entity.NewsImage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NewsImageRepository extends JpaRepository<NewsImage, Long> {
}
