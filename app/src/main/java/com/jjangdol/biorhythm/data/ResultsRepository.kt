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

    /**
     * 특정 날짜의 결과 리스트를 실시간 스트리밍 (부서 필터링 포함)
     * 동일 부서에 한해 필터링됨
     * */
    fun watchResultsByDate(date: LocalDate, context: Context): Flow<List<ChecklistResult>> = callbackFlow {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val myDeptStr = prefs.getString("dept", "") ?: ""

        if (myDeptStr.isEmpty() || myDeptStr == "미등록") {
            trySend(emptyList())
            awaitClose { }
            return@callbackFlow
        }

        val dateString = date.format(fmt)

        val query = db.collection("results")
            .document(dateString)
            .collection("entries")
            .whereEqualTo("dept", myDeptStr)

        val sub = query.addSnapshotListener { snap, e ->

            if (e != null) {
                close(e)
                return@addSnapshotListener
            }

            if (snap == null || snap.isEmpty) {
                trySend(emptyList())
                return@addSnapshotListener
            }

            val allResults = snap.documents.mapNotNull { ds ->
                try {
                    val data = ds.data ?: return@mapNotNull null

                    // 문서 ID에서 사번과 횟수 추출 (예: "001660_1")
                    val docId = ds.id
                    val attemptNumber = docId.substringAfterLast("_", "1").toIntOrNull() ?: 1

                    ChecklistResult(
                        userId = data["empNum"] as? String ?: data["userId"] as? String ?: "",
                        name = data["name"] as? String ?: "",
                        dept = data["dept"] as? String ?: "",
                        date = data["date"] as? String ?: "",
                        time = data["time"] as? String ?: "",
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
                    null
                }
            }.sortedByDescending { it.timestamp }

            trySend(allResults)
        }

        awaitClose {
            sub.remove()
        }
    }
}