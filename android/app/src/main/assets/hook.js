/*
 * TikTok 피드 캡처 후킹 스크립트.
 *
 * 페이지의 어떤 스크립트보다 먼저 실행되어 fetch / XMLHttpRequest 를 감싼다.
 * 관심 엔드포인트의 응답 본문을 그대로 네이티브로 넘기는 것이 전부다 —
 * 파싱·저장은 전부 네이티브가 한다. document-start 스크립트는 페이지 로딩을
 * 블로킹하므로 여기서 하는 일은 최소여야 한다.
 *
 * 네이티브로 넘기는 형식:  url  SEP  timestamp  SEP  body
 * 구분자는 U+001F (unit separator). JSON 은 제어문자를 반드시 이스케이프하도록
 * 규정하므로, raw U+001F 는 유효한 JSON 본문 안에 절대 등장할 수 없다.
 */
(function () {
  'use strict';

  // addDocumentStartJavaScript 는 프레임마다, 폴백 경로는 onPageStarted 마다
  // 실행되므로 중복 설치 가드가 필요하다.
  if (window.__ttTraceHooked) return;
  window.__ttTraceHooked = true;

  var SEP = String.fromCharCode(31);
  var HIT = /\/api\/(recommend\/item_list|post\/item_list|item\/detail|search\/(general|item|video))/;

  function noop() {}

  function send(url, body) {
    if (!body) return;
    try {
      if (typeof ttBridge === 'undefined' || !ttBridge) return;
      ttBridge.postMessage(url + SEP + Date.now() + SEP + body);
    } catch (e) {
      /* 브리지가 아직 없거나 원본이 허용 목록 밖이다 — 조용히 넘어간다 */
    }
  }

  // -- fetch ---------------------------------------------------------------
  // 원본 프로미스를 그대로 반환한다. async 래퍼로 감싸면 페이지의 프로미스
  // 체인 타이밍이 바뀌므로 곁가지로 tap 만 한다.
  var origFetch = window.fetch;
  if (typeof origFetch === 'function') {
    window.fetch = function () {
      var promise = origFetch.apply(this, arguments);
      try {
        var first = arguments[0];
        var url = typeof first === 'string' ? first : (first && first.url) || '';
        if (url && HIT.test(url)) {
          // 우리 핸들러가 페이지 핸들러보다 먼저 등록되므로 body 소비 전에 clone 된다.
          promise.then(function (res) {
            try {
              res.clone().text().then(function (text) { send(url, text); }, noop);
            } catch (e) { /* 이미 소비된 응답 */ }
          }, noop);
        }
      } catch (e) { /* 후킹 실패가 페이지를 깨뜨려선 안 된다 */ }
      return promise;
    };
  }

  // -- XMLHttpRequest ------------------------------------------------------
  var origOpen = XMLHttpRequest.prototype.open;
  XMLHttpRequest.prototype.open = function (method, url) {
    try {
      var u = String(url);
      if (HIT.test(u)) {
        this.addEventListener('load', function () {
          var text;
          try {
            text = this.responseText;   // responseType 이 text 계열이 아니면 throw 한다
          } catch (e) {
            return;
          }
          send(u, text);
        });
      }
    } catch (e) {}
    return origOpen.apply(this, arguments);
  };
})();
