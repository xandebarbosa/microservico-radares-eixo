package com.coruja.repositories;

import com.coruja.entities.Rodovia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RodoviaRepository extends JpaRepository<Rodovia, Long> {
    boolean existsByNome(String nome);
}
