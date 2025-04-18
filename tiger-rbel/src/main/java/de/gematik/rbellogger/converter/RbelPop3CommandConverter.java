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

package de.gematik.rbellogger.converter;

import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.facet.*;
import de.gematik.rbellogger.data.pop3.RbelPop3Command;
import de.gematik.rbellogger.util.EmailConversionUtils;
import de.gematik.rbellogger.util.RbelContent;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.StringTokenizer;
import lombok.extern.slf4j.Slf4j;

@ConverterInfo(onlyActivateFor = "pop3")
@Slf4j
public class RbelPop3CommandConverter extends RbelConverterPlugin {

  @Override
  public void consumeElement(final RbelElement element, final RbelConverter context) {
    buildPop3CommandFacet(element)
        .ifPresent(
            facet -> {
              element.addFacet(facet);
              element.addFacet(
                  new RbelRequestFacet(facet.getCommand().getRawStringContent(), true));
            });
  }

  private Optional<RbelPop3CommandFacet> buildPop3CommandFacet(RbelElement element) {
    return Optional.of(element.getContent())
        .filter(c -> c.size() > 4)
        .filter(EmailConversionUtils::endsWithCrLf)
        .flatMap(this::parseCommand)
        .map(command -> getRbelPop3CommandFacet(element, command));
  }

  private RbelPop3CommandFacet getRbelPop3CommandFacet(
      RbelElement element, RbelPop3Command command) {
    var commandBytes = command.name().getBytes(StandardCharsets.UTF_8);
    return RbelPop3CommandFacet.builder()
        .command(RbelElement.wrap(commandBytes, element, command))
        .arguments(parseArguments(element, commandBytes.length + 1))
        .build();
  }

  private Optional<RbelPop3Command> parseCommand(RbelContent c) {
    var shortPrefix =
        new String(c.subArray(0, RbelPop3Command.MAX_LENGTH + 1), StandardCharsets.UTF_8);
    var command = new StringTokenizer(shortPrefix).nextToken();
    try {
      return Optional.of(RbelPop3Command.fromStringIgnoringCase(command));
    } catch (IllegalArgumentException e) {
      // fall through
    }
    return Optional.empty();
  }

  private RbelElement parseArguments(RbelElement element, int argumentsOffset) {
    var content = element.getContent();
    if (content.size() > argumentsOffset + 2) {
      var argumentBytes = content.subArray(argumentsOffset, content.size() - 2);
      return new RbelElement(argumentBytes, element);
    } else {
      return null;
    }
  }
}
