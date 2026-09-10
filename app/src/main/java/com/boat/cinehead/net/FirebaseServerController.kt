package com.boat.cinehead.net

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Tells the VM (via Firebase Realtime Database) to start or stop
 * voice_server.py, and reports back the VM's actual observed state.
 *
 * Flow:
 *   App writes  /server_control = {command: "start"|"stop", ts}
 *   VM (firebase_listener.py) sees the change, starts/stops voice_server.py
 *   VM writes back /server_status = {state: "starting"|"running"|"stopped", ts}
 *   App listens to /server_status to reflect real state in the UI
 */
class FirebaseServerController {

    enum class ServerState { UNKNOWN, STARTING, RUNNING, STOPPED }

    // FirebaseDatabase.getInstance() throws if no default FirebaseApp was initialized (e.g. a
    // build with google-services.json missing/mismatched for this applicationId). Falling back
    // to null here means every method below becomes a no-op instead of crashing the app at
    // Application.onCreate, where this class is constructed unconditionally.
    private val db: FirebaseDatabase? = try {
        FirebaseDatabase.getInstance()
    } catch (e: IllegalStateException) {
        null
    }
    private val controlRef = db?.getReference("server_control")
    private val statusRef = db?.getReference("server_status")

    private var statusListener: ValueEventListener? = null

    fun signalStart() {
        controlRef?.setValue(mapOf("command" to "start", "ts" to System.currentTimeMillis()))
    }

    fun signalStop() {
        controlRef?.setValue(mapOf("command" to "stop", "ts" to System.currentTimeMillis()))
    }

    /** Starts listening for VM-reported status changes. Call [stopObserving] when done. */
    fun observeStatus(onChange: (ServerState) -> Unit) {
        stopObserving()
        val ref = statusRef ?: run {
            onChange(ServerState.UNKNOWN)
            return
        }
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val state = when (snapshot.child("state").getValue(String::class.java)) {
                    "starting" -> ServerState.STARTING
                    "running" -> ServerState.RUNNING
                    "stopped" -> ServerState.STOPPED
                    else -> ServerState.UNKNOWN
                }
                onChange(state)
            }

            override fun onCancelled(error: DatabaseError) {
                onChange(ServerState.UNKNOWN)
            }
        }
        ref.addValueEventListener(listener)
        statusListener = listener
    }

    fun stopObserving() {
        statusListener?.let { statusRef?.removeEventListener(it) }
        statusListener = null
    }
}
