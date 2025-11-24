// app/src/main/java/com/jjangdol/biorhythm/data/ResultsRepository.kt
package com.jjangdol.biorhythm.data

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.jjangdol.biorhythm.model.ChecklistResult
import dagger.hilt.android.scopes.ActivityRetainedScoped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import javax.inject.Inject

@ActivityRetainedScoped
class ResultsRepository @Inject constructor(
    private val db: FirebaseFirestore
) {
    private val fmt = DateTimeFormatter.ISO_DATE

    /** 특정 날짜의 결과 리스트를 실시간 스트리밍 (부서 필터링 포함) */
    fun watchResultsByDate(date: LocalDate, context: Context): Flow<List<ChecklistResult>> = callbackFlow {
        val TAG = "watchResultsByDate"
        Log.d(TAG, "=== Flow 시작 ===")

        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val myDeptStr = prefs.getString("dept", "") ?: ""
        Log.d(TAG, "내 부서: '$myDeptStr'")

        if (myDeptStr.isEmpty() || myDeptStr == "미등록") {
            Log.d(TAG, "부서 없음 - 빈 리스트 반환")
            trySend(emptyList())
            awaitClose {
                Log.d(TAG, "awaitClose (부서 없음)")
            }
            return@callbackFlow
        }

        val dateString = date.format(fmt)
        Log.d(TAG, "조회 날짜: $dateString")

        val col = db.collection("results")
            .document(dateString)
            .collection("entries")
        Log.d(TAG, "Firestore 경로: results/$dateString/entries")

        val sub = col.addSnapshotListener { snap, e ->
            Log.d(TAG, "--- SnapshotListener 트리거 ---")

            if (e != null) {
                Log.e(TAG, "Firestore 에러", e)
                close(e)
                return@addSnapshotListener
            }

            if (snap == null || snap.isEmpty) {
                Log.d(TAG, "데이터 없음")
                trySend(emptyList())
                return@addSnapshotListener
            }

            Log.d(TAG, "문서 개수: ${snap.documents.size}")

            val allResults = snap.documents.mapNotNull { ds ->
                try {
                    val data = ds.data ?: return@mapNotNull null
                    val deptRaw = data["dept"]
                    val dept = when (deptRaw) {
                        is String -> deptRaw
                        is List<*> -> deptRaw.filterIsInstance<String>().joinToString("/")
                        else -> ""
                    }

                    ChecklistResult(
                        userId = data["userId"] as? String ?: "",
                        name = data["name"] as? String ?: "",
                        dept = dept,
                        date = data["date"] as? String ?: "",
                        checklistScore = (data["checklistScore"] as? Number)?.toInt() ?: 0,
                        finalScore = (data["finalScore"] as? Number)?.toInt() ?: 0,
                        finalSafetyScore = (data["finalSafetyScore"] as? Number)?.toInt() ?: 0,
                        ppgScore = (data["ppgScore"] as? Number)?.toInt() ?: 0,
                        pupilScore = (data["pupilScore"] as? Number)?.toInt() ?: 0,
                        tremorScore = (data["tremorScore"] as? Number)?.toInt() ?: 0,
                        safetyLevel = data["safetyLevel"] as? String ?: "",
                        recommendations = (data["recommendations"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                        timestamp = (data["timestamp"] as? Long) ?: 0
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "파싱 실패: ${ds.id}", e)
                    null
                }
            }

            val filteredResults = allResults.filter { result ->
                result.dept.startsWith(myDeptStr)
            }

            Log.d(TAG, "필터링 결과: ${filteredResults.size}개")
            trySend(filteredResults)
        }

        awaitClose {
            Log.d(TAG, "리스너 제거")
            sub.remove()
        }
    }
}