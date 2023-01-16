package com.flipkart.krystal.krystex.config;

import java.util.Map;
import java.util.Optional;

public record DefaultConfigProvider(Map<String, Object> configs) implements ConfigProvider {

  @Override
  public <T> Optional<T> getConfig(String key) {
    //noinspection unchecked
    return Optional.ofNullable((T) configs.get(key));
  }
}
