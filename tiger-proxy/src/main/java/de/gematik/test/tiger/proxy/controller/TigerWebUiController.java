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

package de.gematik.test.tiger.proxy.controller;

import static j2html.TagCreator.*;

import com.google.common.html.HtmlEscapers;
import de.gematik.rbellogger.data.RbelElement;
import de.gematik.rbellogger.data.facet.TracingMessagePairFacet;
import de.gematik.rbellogger.data.util.RbelElementTreePrinter;
import de.gematik.rbellogger.exceptions.RbelPathException;
import de.gematik.rbellogger.renderer.MessageMetaDataDto;
import de.gematik.rbellogger.renderer.RbelHtmlRenderer;
import de.gematik.rbellogger.renderer.RbelHtmlRenderingToolkit;
import de.gematik.rbellogger.util.RbelAnsiColors;
import de.gematik.rbellogger.util.RbelJexlExecutor;
import de.gematik.test.tiger.common.data.config.tigerproxy.TigerProxyConfiguration;
import de.gematik.test.tiger.common.exceptions.TigerJexlException;
import de.gematik.test.tiger.common.jexl.TigerJexlExecutor;
import de.gematik.test.tiger.proxy.TigerProxy;
import de.gematik.test.tiger.proxy.data.*;
import de.gematik.test.tiger.proxy.exceptions.TigerProxyConfigurationException;
import de.gematik.test.tiger.proxy.exceptions.TigerProxyWebUiException;
import de.gematik.test.tiger.server.TigerBuildPropertiesService;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.jexl3.JexlException;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Element;
import org.springframework.beans.BeansException;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Data
@RequiredArgsConstructor
@RestController
@RequestMapping({"webui", "/"})
@Validated
@Slf4j
public class TigerWebUiController implements ApplicationContextAware {

  private static final String ATTR_DATA_BS_TARGET = "data-bs-target";
  private static final String ATTR_DATA_BS_TOGGLE = "data-bs-toggle";
  private static final String ATTR_ARIA_HASPOPUP = "aria-haspopup";
  private static final String ATTR_ARIA_CONTROLS = "aria-controls";
  private static final String ATTR_ON_CLICK = "onClick";
  private static final String CSS_BTN_DARK = "btn btn-dark";
  private static final String CSS_BTN_OUTLINE_SUCCESS = "btn btn-outline-success";
  private static final String CSS_COLOR_INHERIT = "color:inherit;";
  private static final String CSS_DROPDOWN_TOGGLE_BTN_BTN_DARK = CSS_BTN_DARK + " dropdown-toggle";
  private static final String CSS_DROPDOWN_ITEM = "dropdown-item";
  private static final String CSS_NAVBAR_ITEM = "navbar-item test-navbar-item";
  private static final String CSS_NAVBAR_ITEM_NOT4EMBEDDED =
      CSS_NAVBAR_ITEM + " not4embedded test-webui-navbar-item-notembedded";
  private static final String DROPDOWN_MENU = "dropdown-menu";
  private static final String VALUE_MODAL = "modal";
  private static final String HIDE_QUIT = "display:none;";
  private static final String BUTTON_GROUP_DROPUP = "btn-group dropup";

  /**
   * in error responses on http requests, this token causes problems so remove it for better
   * handling on client side
   */
  public static final String REGEX_STATUSCODE_TOKEN = ".*:\\d* ";

  public static final String DROPDOWN = "dropdown";
  private TigerProxy tigerProxy;
  private final RbelHtmlRenderer renderer;

  private final TigerProxyConfiguration proxyConfiguration;
  private ApplicationContext applicationContext;

  public final SimpMessagingTemplate template;
  private final TigerBuildPropertiesService buildProperties;

  private static final String WS_NEWMESSAGES = "/topic/ws";

  @PostConstruct
  public void addWebSocketListener() {
    renderer.setSubTitle(getVersionStringAsRawHtml() + renderer.getSubTitle());
  }

  public void informClientOfNewMessageArrival(RbelElement element) {
    log.trace(
        "Pushing new message (uUID: {}) from proxy {} to webUI-clients",
        element.getUuid(),
        tigerProxy.proxyName());
    template.convertAndSend(WS_NEWMESSAGES, element.getUuid());
  }

  @Override
  public void setApplicationContext(final ApplicationContext appContext) throws BeansException {
    this.applicationContext = appContext;
  }

  @GetMapping(value = "/trafficLog*.tgr", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
  public String downloadTraffic(
      @RequestParam(name = "lastMsgUuid", required = false) final String lastMsgUuid,
      @RequestParam(name = "filterCriterion", required = false) final String filterCriterion,
      @RequestParam(name = "pageSize", required = false) final Optional<Integer> pageSize,
      HttpServletResponse response) {
    int actualPageSize =
        pageSize.orElse(getProxyConfiguration().getMaximumTrafficDownloadPageSize());
    final List<RbelElement> filteredMessages =
        loadMessagesMatchingFilter(lastMsgUuid, filterCriterion);
    final int returnedMessages = Math.min(filteredMessages.size(), actualPageSize);
    response.addHeader("available-messages", String.valueOf(filteredMessages.size()));
    response.addHeader("returned-messages", String.valueOf(returnedMessages));

    final String result =
        filteredMessages.stream()
            .limit(actualPageSize)
            .map(tigerProxy.getRbelFileWriter()::convertToRbelFileString)
            .collect(Collectors.joining("\n\n"));

    if (!result.isEmpty()) {
      response.addHeader("last-uuid", filteredMessages.get(returnedMessages - 1).getUuid());
    }
    return result;
  }

  @GetMapping(value = "/tiger-report*.html", produces = MediaType.TEXT_HTML_VALUE)
  public String downloadHtml(
      @RequestParam(name = "lastMsgUuid", required = false) final String lastMsgUuid,
      @RequestParam(name = "filterCriterion", required = false) final String filterCriterion) {
    var rbelRenderer = new RbelHtmlRenderer();
    rbelRenderer.setVersionInfo(buildProperties.tigerVersionAsString());
    rbelRenderer.setTitle(
        "RbelLog für "
            + tigerProxy.getName().orElse("Tiger Proxy - Port")
            + ":"
            + tigerProxy.getProxyPort());

    final List<RbelElement> rbelMessages = loadMessagesMatchingFilter(lastMsgUuid, filterCriterion);
    return rbelRenderer.doRender(rbelMessages);
  }

  @GetMapping(value = "", produces = MediaType.TEXT_HTML_VALUE)
  public String getUI(@RequestParam(name = "embedded", defaultValue = "false") boolean embedded) {
    String html = renderer.getEmptyPage(proxyConfiguration.isLocalResources());
    // hide sidebar
    String targetDiv;
    if (embedded) {
      targetDiv = "<div class=\"col msglist embeddedlist\" id=\"rbelembeddedlist\">";
    } else {
      targetDiv = "<div class=\"col ms-6 msglist\" id=\"rbelmsglist\">";
    }
    html = addTigerProxyJsAndCss(html.replace("<div class=\"col ms-6\">", targetDiv));

    String navbar;

    String showQuit = tigerProxy.getTigerProxyConfiguration().isStandalone() ? "" : HIDE_QUIT;

    if (embedded) {
      navbar = createNavbar(tigerProxy, "margin-bottom: 3.5em;", "margin-inline: auto;", showQuit);
    } else {
      navbar = createNavbar(tigerProxy, "", "", showQuit);
    }

    return html.replace(
        "<div id=\"navbardiv\"></div>",
        navbar
            + loadResourceToString("/routeModal.html")
            + loadResourceToString("/filterModal.html")
            + loadResourceToString("/jexlModal.html")
            + loadResourceToString("/saveModal.html")
            + loadResourceToString("/errorMessagesModal.html"));
  }

  private String getVersionStringAsRawHtml() {
    return "<div class=\"is-size-6\" style=\"text-align: right;margin-bottom:"
        + " 1rem!important;margin-right: 1.5em;\">"
        + buildProperties.tigerVersionAsString()
        + " - "
        + buildProperties.tigerBuildDateAsString()
        + "</div>";
  }

  private String createNavbar(
      TigerProxy tigerProxy, String styleNavbar, String styleNavbarStart, String styleQuit) {
    return nav()
        .withClass("navbar bg-dark fixed-bottom")
        .withId("webui-navbar")
        .withStyle(styleNavbar)
        .with(
            div()
                .withClass("container-fluid")
                .with(
                    div()
                        .withStyle(styleNavbarStart)
                        .with(
                            div()
                                .withClass(CSS_NAVBAR_ITEM_NOT4EMBEDDED)
                                .with(
                                    button()
                                        .withId("routeModalBtn")
                                        .withClass(CSS_BTN_OUTLINE_SUCCESS)
                                        .attr(ATTR_DATA_BS_TARGET, "#routeModalDialog")
                                        .attr(ATTR_DATA_BS_TOGGLE, VALUE_MODAL)
                                        .with(
                                            i().withClass("fas fa-exchange-alt"),
                                            span("Routes")
                                                .withClass("ms-2")
                                                .withStyle(CSS_COLOR_INHERIT))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM_NOT4EMBEDDED)
                                .with(
                                    button()
                                        .withId("scrollLockBtn")
                                        .withClass(CSS_BTN_DARK)
                                        .with(
                                            div().withId("scrollLockLed").withClass("led"),
                                            span("Scroll Lock"))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM)
                                .with(
                                    div()
                                        .withId("dropdown-hide-button")
                                        .withClass(BUTTON_GROUP_DROPUP)
                                        .with(
                                            button()
                                                .withClass(CSS_DROPDOWN_TOGGLE_BTN_BTN_DARK)
                                                .attr(ATTR_DATA_BS_TOGGLE, DROPDOWN)
                                                .attr(ATTR_ARIA_HASPOPUP, "true")
                                                .attr(ATTR_ARIA_CONTROLS, DROPDOWN_MENU)
                                                .attr("type", "button")
                                                .with(
                                                    span()
                                                        .withClass("icon is-small")
                                                        .with(
                                                            i().withClass(
                                                                    "fa-solid fa-toggle-on"))),
                                            div()
                                                .withClass(DROPDOWN_MENU + " bg-dark")
                                                .attr("type", "menu")
                                                .with(
                                                    div()
                                                        .withClass(CSS_DROPDOWN_ITEM)
                                                        .with(
                                                            button()
                                                                .withId(
                                                                    "collapsibleMessageHeaderBtn")
                                                                .withClass(CSS_BTN_DARK)
                                                                .with(
                                                                    div()
                                                                        .withId(
                                                                            "collapsibleMessageHeader")
                                                                        .withClass("led"),
                                                                    span("Hide Headers"))),
                                                    div()
                                                        .withClass(CSS_DROPDOWN_ITEM)
                                                        .with(
                                                            button()
                                                                .withId(
                                                                    "collapsibleMessageDetailsBtn")
                                                                .withClass(CSS_BTN_DARK)
                                                                .with(
                                                                    div()
                                                                        .withId(
                                                                            "collapsibleMessageDetails")
                                                                        .withClass("led"),
                                                                    span("Hide Details")))))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM)
                                .with(
                                    button()
                                        .withId("filterModalBtn")
                                        .withClass(CSS_BTN_OUTLINE_SUCCESS)
                                        .attr(ATTR_DATA_BS_TARGET, "#filterModalDialog")
                                        .attr(ATTR_DATA_BS_TOGGLE, VALUE_MODAL)
                                        .with(
                                            i().withClass("fas fa-filter"),
                                            span("Filter")
                                                .withClass("ms-2")
                                                .withStyle(CSS_COLOR_INHERIT))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM_NOT4EMBEDDED)
                                .with(
                                    button()
                                        .withId("resetMsgs")
                                        .withClass("btn btn-outline-danger")
                                        .with(
                                            i().withClass("far fa-trash-alt"),
                                            span("Reset")
                                                .withClass("ms-2")
                                                .withStyle(CSS_COLOR_INHERIT))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM)
                                .with(
                                    div()
                                        .withId("dropdown-page-selection")
                                        .withClass(BUTTON_GROUP_DROPUP)
                                        .with(
                                            button()
                                                .withClass(CSS_DROPDOWN_TOGGLE_BTN_BTN_DARK)
                                                .attr(ATTR_DATA_BS_TOGGLE, DROPDOWN)
                                                .attr(ATTR_ARIA_HASPOPUP, "true")
                                                .attr(ATTR_ARIA_CONTROLS, DROPDOWN_MENU)
                                                .attr("type", "button")
                                                .with(
                                                    span()
                                                        .withText("Page 1")
                                                        .withId("pageNumberDisplay")),
                                            div()
                                                .withClass(DROPDOWN_MENU)
                                                .attr("type", "menu")
                                                .with(
                                                    div()
                                                        .withClass("dropdown-content")
                                                        .withId("pageSelector")
                                                        .with(
                                                            a().withClass(CSS_DROPDOWN_ITEM)
                                                                .attr(
                                                                    ATTR_ON_CLICK,
                                                                    "setPageNumber(0)")
                                                                .withText("1"))))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM)
                                .with(
                                    div()
                                        .withId("dropdown-page-size")
                                        .withClass(BUTTON_GROUP_DROPUP)
                                        .with(
                                            button()
                                                .withClass(CSS_DROPDOWN_TOGGLE_BTN_BTN_DARK)
                                                .attr(ATTR_DATA_BS_TOGGLE, DROPDOWN)
                                                .attr(ATTR_ARIA_HASPOPUP, "true")
                                                .attr(ATTR_ARIA_CONTROLS, DROPDOWN_MENU)
                                                .with(
                                                    span()
                                                        .withId("pageSizeDisplay")
                                                        .withText("Size")),
                                            div()
                                                .withClass(DROPDOWN_MENU)
                                                .attr("role", "menu")
                                                .with(
                                                    div()
                                                        .withClass("dropdown-content")
                                                        .withId("sizeSelector")
                                                        .with(
                                                            a().withClass(CSS_DROPDOWN_ITEM)
                                                                .attr(
                                                                    ATTR_ON_CLICK,
                                                                    "setPageSize(10);")
                                                                .withText("10"),
                                                            a().withClass(CSS_DROPDOWN_ITEM)
                                                                .attr(
                                                                    ATTR_ON_CLICK,
                                                                    "setPageSize(20);")
                                                                .withText("20"),
                                                            a().withClass(CSS_DROPDOWN_ITEM)
                                                                .attr(
                                                                    ATTR_ON_CLICK,
                                                                    "setPageSize(50);")
                                                                .withText("50"),
                                                            a().withClass(CSS_DROPDOWN_ITEM)
                                                                .attr(
                                                                    ATTR_ON_CLICK,
                                                                    "setPageSize(100);")
                                                                .withText("100"))))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM_NOT4EMBEDDED)
                                .with(
                                    button()
                                        .withId("importMsgs")
                                        .withClass(CSS_BTN_OUTLINE_SUCCESS)
                                        .with(
                                            i().withClass("fa-solid fa-file-import"),
                                            span("Import")
                                                .withClass("ms-2")
                                                .withStyle(CSS_COLOR_INHERIT))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM)
                                .with(
                                    button()
                                        .withId("exportMsgs")
                                        .withClass(CSS_BTN_OUTLINE_SUCCESS)
                                        .with(
                                            i().withClass("fa-solid fa-file-export"),
                                            span("Export")
                                                .withClass("ms-2")
                                                .withStyle(CSS_COLOR_INHERIT))),
                            div()
                                .withClass(CSS_NAVBAR_ITEM_NOT4EMBEDDED)
                                .with(
                                    span("Proxy port "),
                                    b(String.valueOf(tigerProxy.getProxyPort())).withClass("ms-3")),
                            div()
                                .withClass(CSS_NAVBAR_ITEM_NOT4EMBEDDED)
                                .withStyle(styleQuit)
                                .with(
                                    button()
                                        .withId("quitProxy")
                                        .withClass("btn btn-outline-danger")
                                        .with(
                                            i().withClass("fas fa-power-off"),
                                            span("Quit")
                                                .withClass("ms-2")
                                                .withStyle(CSS_COLOR_INHERIT))))))
        .render();
  }

  private String addTigerProxyJsAndCss(String html) {
    var jsoup = Jsoup.parse(html);
    final Element mainWebUiScript = jsoup.getElementById("mainWebUiScript");

    if (mainWebUiScript == null) {
      throw new TigerProxyWebUiException("Unable to embed proxy scripts into webui!");
    }
    var addScript = new Element("script");
    addScript.attr("type", "module");
    addScript.attr("id", "tigerProxyScript");
    addScript.appendChild(new DataNode(loadResourceToString("/tigerProxy.js")));
    mainWebUiScript.after(addScript);

    final Element rbelCss = jsoup.getElementById("rbel_css");
    if (rbelCss == null) {
      throw new TigerProxyWebUiException("Unable to embed proxy css into webui!");
    }

    var addCss = new Element("style");
    addCss.attr("id", "tigerProxy_css");
    addCss.appendChild(new DataNode(loadResourceToString("/proxy.css")));
    rbelCss.after(addCss);
    return jsoup.html();
  }

  private String loadResourceToString(String resourceName) {
    final InputStream resource = getClass().getResourceAsStream(resourceName);
    if (resource == null) {
      throw new TigerProxyConfigurationException(
          "Unable to load resource '" + resourceName + "' !");
    }
    try {
      return IOUtils.toString(resource, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new TigerProxyWebUiException(
          "Exception while loading resource '" + resourceName + "'", e);
    }
  }

  @GetMapping(value = "/css/{cssfile}", produces = "text/css")
  public String getCSS(@PathVariable("cssfile") String cssFile) throws IOException {
    try (InputStream is = getClass().getResourceAsStream("/css/" + cssFile)) {
      if (is == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "css file " + cssFile + " not found");
      }
      return IOUtils.toString(is, StandardCharsets.UTF_8);
    }
  }

  @GetMapping(value = "/testJexlQuery", produces = MediaType.APPLICATION_JSON_VALUE)
  public JexlQueryResponseDto testJexlQuery(
      @RequestParam(name = "msgUuid") final String msgUuid,
      @RequestParam(name = "query") final String query) {
    final RbelElement targetMessage =
        getTigerProxy().getRbelLogger().getMessageHistory().stream()
            .filter(msg -> msg.getUuid().equals(msgUuid))
            .findFirst()
            .orElseThrow();
    final Map<String, Object> messageContext =
        TigerJexlExecutor.buildJexlMapContext(targetMessage, Optional.empty());
    try {
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml(createRbelTreeForElement(targetMessage, false))
          .matchSuccessful(TigerJexlExecutor.matchesAsJexlExpression(targetMessage, query))
          .messageContext(messageContext)
          .build();
    } catch (JexlException | TigerJexlException jexlException) {
      log.warn("Failed to perform JEXL query '" + query + "'", jexlException);
      String msg = jexlException.getMessage();
      msg = msg.replaceAll(REGEX_STATUSCODE_TOKEN, "");
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml(createRbelTreeForElement(targetMessage, false))
          .errorMessage(msg)
          .build();

    } catch (RuntimeException rte) {
      log.warn("Runtime failure while performing JEXL query '" + query + "'", rte);
      String msg = rte.getMessage();
      msg = msg.replaceAll(REGEX_STATUSCODE_TOKEN, "");
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml(createRbelTreeForElement(targetMessage, false))
          .errorMessage(msg)
          .build();
    }
  }

  @GetMapping(value = "/testRbelExpression", produces = MediaType.APPLICATION_JSON_VALUE)
  @SuppressWarnings("java:S5852")
  public JexlQueryResponseDto testRbelExpression(
      @RequestParam(name = "msgUuid") final String msgUuid,
      @RequestParam(name = "rbelPath") final String rbelPath) {
    List<RbelElement> targetElements = null;
    try {
      targetElements =
          getTigerProxy().getRbelLogger().getMessageHistory().stream()
              .filter(msg -> msg.getUuid().equals(msgUuid))
              .map(msg -> msg.findRbelPathMembers(rbelPath))
              .flatMap(List::stream)
              .toList();
      if (targetElements.isEmpty()) {
        return JexlQueryResponseDto.builder().build();
      }
    } catch (RbelPathException rbelPathException) {
      log.warn("Failed to perform RBelPath query '" + rbelPath + "'", rbelPathException);
      String msg = rbelPathException.getMessage();
      msg = msg.replaceAll(REGEX_STATUSCODE_TOKEN, "");
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml("<span>Error while parsing RbelPath '" + msg + "'</span>")
          .errorMessage(msg)
          .build();
    }
    try {
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml(createRbelTreeForElement(targetElements.get(0), true, rbelPath))
          .elements(
              targetElements.stream()
                  .map(RbelElement::findNodePath)
                  .map(
                      key ->
                          key.endsWith(".content") && !rbelPath.endsWith(".content")
                              ? "$." + key.substring(0, key.length() - 8)
                              : "$." + key)
                  .toList())
          .build();
    } catch (JexlException | TigerJexlException jexlException) {
      log.warn("Failed to perform RBelPath query '" + rbelPath + "'", jexlException);
      String msg = jexlException.getMessage();
      msg = msg.replaceAll(REGEX_STATUSCODE_TOKEN, "");
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml("<span>RbelPath is invalid '" + msg + "'</span>")
          .errorMessage(msg)
          .build();

    } catch (RuntimeException rte) {
      log.warn("Runtime failure while performing RbelPath query '" + rbelPath + "'", rte);
      String msg = rte.getMessage();
      msg = msg.replaceAll(REGEX_STATUSCODE_TOKEN, "");
      return JexlQueryResponseDto.builder()
          .rbelTreeHtml("<span>Error while parsing RbelPath '" + msg + "'</span>")
          .errorMessage(msg)
          .build();
    }
  }

  private String createRbelTreeForElement(
      RbelElement targetElement, boolean addJexlResponseLinkCssClass) {
    return createRbelTreeForElement(targetElement, addJexlResponseLinkCssClass, "");
  }

  private String createRbelTreeForElement(
      RbelElement targetElement, boolean addJexlResponseLinkCssClass, String rbelPath) {

    RbelElement rootElement =
        targetElement
            .getKey()
            .filter(key -> key.endsWith("content") && !rbelPath.endsWith("content"))
            .map(key -> targetElement.getParentNode())
            .orElse(targetElement);

    return HtmlEscapers.htmlEscaper()
        .escape(
            RbelElementTreePrinter.builder()
                .rootElement(rootElement)
                .printFacets(false)
                .build()
                .execute())
        .replace(RbelAnsiColors.RESET.toString(), "</span>")
        .replace(
            RbelAnsiColors.RED_BOLD.toString(),
            "<span class='text-warning "
                + (addJexlResponseLinkCssClass ? "jexlResponseLink' style='cursor: pointer;'" : "'")
                + ">")
        .replace(RbelAnsiColors.CYAN.toString(), "<span class='text-info'>")
        .replace(
            RbelAnsiColors.YELLOW_BRIGHT.toString(),
            "<span class='text-danger has-text-weight-bold'>")
        .replace(RbelAnsiColors.GREEN.toString(), "<span class='text-warning'>")
        .replace(RbelAnsiColors.BLUE.toString(), "<span class='text-success'>")
        .replace("\n", "<br/>");
  }

  @GetMapping(value = "/webfonts/{fontfile}", produces = "text/css")
  public ResponseEntity<byte[]> getWebFont(@PathVariable("fontfile") String fontFile)
      throws IOException {
    try (InputStream is = getClass().getResourceAsStream("/webfonts/" + fontFile)) {
      if (is == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "webfont file " + fontFile + " not found");
      }
      return new ResponseEntity<>(IOUtils.toByteArray(is), HttpStatus.OK);
    }
  }

  @GetMapping(value = "/getMsgAfter", produces = MediaType.APPLICATION_JSON_VALUE)
  public GetMessagesAfterDto getMessagesAfter(
      @RequestParam(name = "lastMsgUuid", required = false) final String lastMsgUuid,
      @RequestParam(name = "filterCriterion", required = false) final String filterCriterion,
      @RequestParam(name = "pageSize", defaultValue = "1000000") final int pageSize,
      @RequestParam(name = "pageNumber", defaultValue = "0") final int pageNumber) {
    log.debug(
        "requesting messages since " + lastMsgUuid + " (filtered by . " + filterCriterion + ")");

    List<RbelElement> msgs = loadMessagesMatchingFilter(lastMsgUuid, filterCriterion);

    var result = new GetMessagesAfterDto();
    result.setLastMsgUuid(lastMsgUuid);
    result.setHtmlMsgList(
        msgs.stream()
            .skip((long) pageNumber * pageSize)
            .limit(pageSize)
            .map(
                msg ->
                    HtmlMessage.builder()
                        .html(new RbelHtmlRenderingToolkit(renderer).convertMessage(msg).render())
                        .uuid(msg.getUuid())
                        .sequenceNumber(MessageMetaDataDto.getElementSequenceNumber(msg))
                        .build())
            .toList());
    result.setMetaMsgList(msgs.stream().map(MessageMetaDataDto::createFrom).toList());
    result.setTotalMsgCount(tigerProxy.getRbelLogger().getMessageHistory().size());
    log.info(
        "Returning {} messages ({} in menu, {} filtered) of total {}",
        result.getHtmlMsgList().size(),
        result.getMetaMsgList().size(),
        msgs.size(),
        tigerProxy.getRbelLogger().getMessageHistory().size());
    return result;
  }

  private List<RbelElement> loadMessagesMatchingFilter(String lastMsgUuid, String filterCriterion) {
    return getTigerProxy().getRbelLogger().getMessageHistory().stream()
        .filter(
            msg -> {
              if (StringUtils.isEmpty(filterCriterion)) {
                return true;
              }
              if (filterCriterion.startsWith("\"") && filterCriterion.endsWith("\"")) {
                final String textFilter =
                    filterCriterion.substring(1, filterCriterion.length() - 1);
                return RbelJexlExecutor.matchAsTextExpression(msg, textFilter)
                    || RbelJexlExecutor.matchAsTextExpression(findPartner(msg), textFilter);
              } else {
                return TigerJexlExecutor.matchesAsJexlExpression(
                        msg, filterCriterion, Optional.empty())
                    || TigerJexlExecutor.matchesAsJexlExpression(
                        findPartner(msg), filterCriterion, Optional.empty());
              }
            })
        .dropWhile(messageIsBefore(lastMsgUuid))
        .filter(msg -> !msg.getUuid().equals(lastMsgUuid))
        .toList();
  }

  private static Predicate<RbelElement> messageIsBefore(String lastMsgUuid) {
    return msg -> {
      if (StringUtils.isEmpty(lastMsgUuid)) {
        return false;
      } else {
        return !msg.getUuid().equals(lastMsgUuid);
      }
    };
  }

  private RbelElement findPartner(RbelElement msg) {
    return msg.getFacet(TracingMessagePairFacet.class)
        .map(
            pairFacet -> {
              if (pairFacet.getRequest() == msg) {
                return pairFacet.getResponse();
              } else {
                return pairFacet.getRequest();
              }
            })
        .orElse(null);
  }

  @GetMapping(value = "/resetMsgs", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResetMessagesDto resetMessages() {
    log.info("Resetting currently recorded messages on rbel logger..");
    int size = getTigerProxy().getRbelLogger().getMessageHistory().size();
    ResetMessagesDto result = new ResetMessagesDto();
    result.setNumMsgs(size);
    getTigerProxy().getRbelLogger().clearAllMessages();
    return result;
  }

  @GetMapping(value = "/quit", produces = MediaType.APPLICATION_JSON_VALUE)
  public void quitProxy(
      @RequestParam(name = "noSystemExit", required = false) final String noSystemExit) {
    log.info("Shutting down tiger standalone proxy at port " + tigerProxy.getProxyPort() + "...");
    tigerProxy.close();
    log.info("Shutting down tiger standalone proxy ui...");
    int exitCode = SpringApplication.exit(applicationContext);
    if (exitCode != 0) {
      log.warn("Exit of tiger proxy ui not successful - exit code: " + exitCode);
    }
    if (StringUtils.isEmpty(noSystemExit)) {
      System.exit(0);
    }
  }

  @PostMapping(value = "/importTraffic")
  public void importTrafficFromFile(@RequestBody String rawTraffic) {
    tigerProxy.readTrafficFromString(rawTraffic);
  }
}
