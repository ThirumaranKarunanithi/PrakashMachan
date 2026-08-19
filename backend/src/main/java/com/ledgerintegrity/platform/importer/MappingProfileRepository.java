package com.ledgerintegrity.platform.importer;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DAT-004: mapping profiles are saved and reused per client/system.
 * MVP: seeded from classpath JSON files (src/main/resources/mappings/*.json);
 * CRUD over the API arrives with persistence.
 */
@Repository
public class MappingProfileRepository {

    private final Map<String, MappingProfile> profiles = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public MappingProfileRepository(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadSeedProfiles() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath:mappings/*.json");
            for (Resource r : resources) {
                MappingProfile p = objectMapper.readValue(r.getInputStream(), MappingProfile.class);
                profiles.put(p.name(), p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load seed mapping profiles", e);
        }
    }

    public Optional<MappingProfile> find(String name) {
        return Optional.ofNullable(profiles.get(name));
    }

    public List<MappingProfile> findAll() {
        return List.copyOf(profiles.values());
    }

    public void save(MappingProfile profile) {
        profiles.put(profile.name(), profile);
    }
}
