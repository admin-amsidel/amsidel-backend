/*
 * Copyright (c) 2026 AMSIDEL Technologies. All rights reserved.
 * Designed and developed by AMSIDEL Technologies.
 */
package com.amsidel.backend.service;

import com.amsidel.backend.dto.ProductRequest;
import com.amsidel.backend.entity.Product;
import com.amsidel.backend.exception.ResourceNotFoundException;
import com.amsidel.backend.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional(readOnly = true)
    public List<Product> findAll(boolean activeOnly) {
        return activeOnly ? productRepository.findByActiveTrueOrderByNameAsc() : productRepository.findAll();
    }

    @Transactional(readOnly = true)
    public Product findById(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }

    @Transactional
    public Product create(ProductRequest request) {
        Product product = new Product();
        apply(product, request);
        return productRepository.save(product);
    }

    @Transactional
    public Product update(Long id, ProductRequest request) {
        Product product = findById(id);
        apply(product, request);
        return productRepository.save(product);
    }

    @Transactional
    public void delete(Long id) {
        productRepository.delete(findById(id));
    }

    private void apply(Product product, ProductRequest request) {
        product.setName(request.getName());
        product.setCategory(request.getCategory());
        product.setDescription(request.getDescription());
        product.setPrice(request.getPrice());
        if (request.getActive() != null) {
            product.setActive(request.getActive());
        }
    }
}
