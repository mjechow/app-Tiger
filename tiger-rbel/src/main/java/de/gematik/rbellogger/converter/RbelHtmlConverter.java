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
import de.gematik.rbellogger.data.RbelMultiMap;
import de.gematik.rbellogger.data.facet.RbelXmlFacet;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;

@Slf4j
class RbelHtmlConverter {

  private static final String XML_TEXT_KEY = "text";
  private static final byte[] HTML_END_TAG = "</html>".getBytes();

  Optional<Document> parseHtml(RbelElement element) {
    if (!element
        .getContent()
        .endsTrimmedWithIgnoreCase(HTML_END_TAG, element.getElementCharset())) {
      return Optional.empty();
    }
    return Optional.ofNullable(element.getRawStringContent()).map(String::trim).map(Jsoup::parse);
  }

  void buildXmlElementForNode(Node branch, RbelElement parentElement, RbelConverter converter) {
    final RbelMultiMap<RbelElement> childElements = new RbelMultiMap<>();
    parentElement.addFacet(RbelXmlFacet.builder().childElements(childElements).build());
    for (Node childNode : branch.childNodes()) {
      if (childNode instanceof TextNode textNode) {
        childElements.put(
            XML_TEXT_KEY, converter.convertElement(textNode.getWholeText(), parentElement));
      } else {
        var rbelChildElement = new RbelElement(childNode.toString().getBytes(), parentElement);
        childElements.put(childNode.nodeName(), rbelChildElement);
        buildXmlElementForNode(childNode, rbelChildElement, converter);
      }
    }
    if (branch instanceof Element) {
      for (Attribute attribute : branch.attributes()) {
        childElements.put(
            attribute.getKey(), converter.convertElement(attribute.getValue(), parentElement));
      }
    }
  }
}
