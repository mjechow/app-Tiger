/*
 * Copyright 2024 gematik GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.gematik.rbellogger.key;

import de.gematik.rbellogger.converter.RbelConverterPlugin;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.facet.RbelJsonFacet;
import java.security.Key;
import java.util.*;
import java.util.stream.Stream;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;

@Slf4j
public class RbelKeyManager {

  public static final RbelConverterPlugin RBEL_IDP_TOKEN_KEY_LISTENER =
      RbelConverterPlugin.createPlugin(
          (element, converter) ->
              Optional.ofNullable(element)
                  .filter(el -> el.hasFacet(RbelJsonFacet.class))
                  .filter(el -> el.getKey().filter(key -> key.equals("token_key")).isPresent())
                  .flatMap(el -> el.getFirst("content"))
                  .map(RbelElement::getRawStringContent)
                  .map(
                      tokenB64 -> {
                        try {
                          return Base64.getUrlDecoder().decode(tokenB64);
                        } catch (Exception e1) {
                          try {
                            return Base64.getDecoder().decode(tokenB64);
                          } catch (Exception e2) {
                            return null;
                          }
                        }
                      })
                  .map(tokenKeyBytes -> new SecretKeySpec(tokenKeyBytes, "AES"))
                  .ifPresent(
                      aesKey ->
                          converter
                              .getRbelKeyManager()
                              .addKey("token_key", aesKey, RbelKey.PRECEDENCE_KEY_FOLDER)));

  private final List<RbelKey> keyList = new ArrayList<>();
  private final Set<RbelKey> keySet = new HashSet<>();

  public synchronized RbelKeyManager addAll(Map<String, RbelKey> keys) {
    keyList.addAll(keys.values());
    return this;
  }

  public synchronized Optional<RbelKey> addKey(RbelKey rbelKey) {
    if (rbelKey.getKey() == null) {
      return Optional.empty();
    }

    if (keyIsPresentInList(rbelKey)) {
      log.trace("Skipping adding key: Key is already known!");
      return Optional.empty();
    } else {
      keyList.add(rbelKey);
      keySet.add(rbelKey);

      log.debug("Added key {} (Now there are {} keys known)", rbelKey.getKeyName(), keyList.size());
      return Optional.of(rbelKey);
    }
  }

  public synchronized Optional<RbelKey> addKey(String keyId, Key key, int precedence) {
    final RbelKey rbelKey =
        RbelKey.builder().keyName(keyId).key(key).precedence(precedence).build();

    return addKey(rbelKey);
  }

  private synchronized boolean keyIsPresentInList(RbelKey key) {
    log.atTrace()
        .addArgument(() -> Hex.toHexString(key.getHash()))
        .log("Checking if key is already known: {}");
    return keyList.stream().anyMatch(oldKey -> oldKey.equals(key));
  }

  public synchronized Stream<RbelKey> getAllKeys() {
    return new ArrayList<>(keyList).stream().sorted(Comparator.comparing(RbelKey::getPrecedence));
  }

  public synchronized Optional<RbelKey> findCorrespondingPrivateKey(String rbelKey) {
    return getAllKeys()
        .filter(candidate -> candidate.getMatchingPublicKey().isPresent())
        .filter(
            candidate ->
                Objects.equals(candidate.getMatchingPublicKey().get().getKeyName(), rbelKey))
        .findFirst();
  }

  public synchronized Optional<RbelKey> findKeyByName(String keyName) {
    return getAllKeys()
        .filter(
            candidate -> candidate.getKeyName() != null && candidate.getKeyName().equals(keyName))
        .findFirst();
  }
}
