package com.testpilot.testing.generation;

import com.testpilot.common.exception.InvalidRequestException;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

@Component
public class LanguageAdapterRegistry {
    private final List<LanguageTestAdapter> adapters;
    public LanguageAdapterRegistry(List<LanguageTestAdapter> adapters) { this.adapters = List.copyOf(adapters); }
    public Optional<LanguageTestAdapter> find(String path) { return adapters.stream().filter(a -> a.supports(path)).findFirst(); }
    public LanguageTestAdapter require(String path) { return find(path).orElseThrow(() -> new InvalidRequestException("No generation adapter for: " + path)); }
}
