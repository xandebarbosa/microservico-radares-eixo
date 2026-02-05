package com.coruja.repositories;

import com.coruja.entities.KmPraca;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface KmPracaRepository extends JpaRepository<KmPraca, Long> {
    List<KmPraca> findByPracaId(Long pracaId);
}
