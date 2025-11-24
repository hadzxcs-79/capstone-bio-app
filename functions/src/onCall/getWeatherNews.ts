import * as admin from "firebase-admin";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import * as logger from "firebase-functions/logger";
import { defineSecret } from "firebase-functions/params";
import fetch from "node-fetch";

const db = admin.firestore();
const weatherAlertKey = defineSecret("WEATHER_ALERT_KEY");

// 특보 타입 추출 함수 (예: "강풍주의보", "풍랑주의보")
function extractAlertTypes(title: string): string[] {
  const match = title.match(/\/(.*?)\s+(발표|해제)/);
  if (!match) return [];

  const alertText = match[1];
  // "강풍주의보·풍랑주의보" 형태를 ["강풍주의보", "풍랑주의보"]로 분리
  return alertText.split('·').map(type => type.trim());
}

// 발표/해제 여부 판단
function isIssued(title: string): boolean {
  return title.includes('발표');
}

function isCancelled(title: string): boolean {
  return title.includes('해제');
}

export const getWeatherNews = onCall({ secrets: [weatherAlertKey] }, async (request) => {
  const { STN_ID } = request.data;

  if (!STN_ID) {
    logger.error("STN_ID 값이 요청에 포함되지 않았습니다.", request.data);
    throw new HttpsError("invalid-argument", "STN_ID 값은 필수입니다.");
  }

  logger.info(`클라이언트 요청 수신: STN_ID=${STN_ID}`);

  const now = new Date();
  const kstOffset = 9 * 60 * 60 * 1000;
  const kstNow = new Date(now.getTime() + kstOffset);

  const year = kstNow.getFullYear();
  const month = String(kstNow.getMonth() + 1).padStart(2, '0');
  const day = String(kstNow.getDate()).padStart(2, '0');
  const currentDate = `${year}${month}${day}`;

  const docId = String(STN_ID);
  const weatherNewsDocRef = db.collection("weatherNews").doc(docId);

  // API 호출
  const url = `https://apis.data.go.kr/1360000/WthrWrnInfoService/getWthrWrnList?serviceKey=${weatherAlertKey.value()}&numOfRows=20&pageNo=1&stnId=${STN_ID}&dataType=JSON`;
  logger.info(`API 요청 URL: ${url}`);

  try {
    const response = await fetch(url);
    if (!response.ok) {
      throw new HttpsError("unavailable", `API 요청 실패: ${response.status}`);
    }

    const data: any = await response.json();

    const resultCode = data?.response?.header?.resultCode;
    if (resultCode !== "00") {
      throw new HttpsError("internal", `API 에러: ${data?.response?.header?.resultMsg}`);
    }

    const items = data?.response?.body?.items?.item;
    if (!items) {
      throw new HttpsError("not-found", "API로부터 유효한 데이터를 받지 못했습니다.");
    }

    const itemArray = Array.isArray(items) ? items : [items];

    // 기존 데이터 가져오기
    let existingData: any = { issued: {}, cancelled: {} };
    try {
      const docSnap = await weatherNewsDocRef.get();
      if (docSnap.exists) {
        existingData = docSnap.data() || { issued: {}, cancelled: {} };

        // 해제 데이터 중 날짜가 다른 것 제거
        if (existingData.cancelled) {
          Object.keys(existingData.cancelled).forEach(key => {
            const cancelledDate = existingData.cancelled[key].date.substring(0, 8);
            if (cancelledDate !== currentDate) {
              delete existingData.cancelled[key];
              logger.info(`[해제 데이터 삭제] 날짜 지남: ${key}`);
            }
          });
        }
      }
    } catch (error) {
      logger.warn("Firestore 데이터 확인 중 오류 발생:", error);
    }

    // 초기화 (없을 경우 대비)
    if (!existingData.issued) existingData.issued = {};
    if (!existingData.cancelled) existingData.cancelled = {};

    // API 데이터 처리
    for (const item of itemArray) {
      const title = item.title;
      const alertTypes = extractAlertTypes(title);
      const dateStr = String(item.tmFc);

      if (isIssued(title)) {
        // 발표: 각 특보 타입별로 저장
        alertTypes.forEach(alertType => {
          existingData.issued[alertType] = {
            title: title,
            date: dateStr,
            tmSeq: item.tmSeq
          };

          // 발표되면 해당 특보의 해제 정보는 삭제
          if (existingData.cancelled[alertType]) {
            delete existingData.cancelled[alertType];
            logger.info(`[해제 데이터 삭제] 재발표됨: ${alertType}`);
          }

          logger.info(`[발표 저장] ${alertType}: ${title}`);
        });
      } else if (isCancelled(title)) {
        // 해제: 해당 특보 타입들을 발표에서 제거하고 해제에 추가
        alertTypes.forEach(alertType => {
          if (existingData.issued[alertType]) {
            delete existingData.issued[alertType];
            logger.info(`[발표 데이터 삭제] ${alertType} 해제됨`);
          }

          existingData.cancelled[alertType] = {
            title: title,
            date: dateStr,
            tmSeq: item.tmSeq
          };
          logger.info(`[해제 저장] ${alertType}: ${title}`);
        });
      }
    }

    // Firestore에 저장
    await weatherNewsDocRef.set(existingData);
    logger.info(`Firestore 저장 완료: STN_ID=${STN_ID}`);

    // 클라이언트에 반환할 데이터 구성
    const responseData = {
      issued: Object.values(existingData.issued),
      cancelled: Object.values(existingData.cancelled)
    };

    return {
      status: "success",
      data: responseData
    };

  } catch (error) {
    logger.error("getWeatherNews 함수 실행 중 오류:", error);
    if (error instanceof HttpsError) {
      throw error;
    } else {
      throw new HttpsError("internal", "알 수 없는 서버 오류가 발생했습니다.");
    }
  }
});