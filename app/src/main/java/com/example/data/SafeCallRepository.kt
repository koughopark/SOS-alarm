package com.example.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

data class EmergencyContact(
    val index: Int,
    val name: String,
    val phone: String
)

class SafeCallRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val settingDao = db.settingDao()
    private val callLogDao = db.callLogDao()

    val guardianPhoneFlow: Flow<String> = settingDao.getSettingFlow("guardian_phone")
        .map { it?.value ?: "" }

    val guardianNameFlow: Flow<String> = settingDao.getSettingFlow("guardian_name")
        .map { it?.value ?: "" }

    val isServiceEnabledFlow: Flow<Boolean> = settingDao.getSettingFlow("service_enabled")
        .map { it?.value?.toBoolean() ?: true }

    val allLogsFlow: Flow<List<CallLogEntity>> = callLogDao.getAllLogs()

    suspend fun saveGuardianInfo(name: String, phone: String) {
        settingDao.insertSetting(SettingEntity("guardian_name", name))
        settingDao.insertSetting(SettingEntity("guardian_phone", phone))
    }

    suspend fun saveServiceEnabled(enabled: Boolean) {
        settingDao.insertSetting(SettingEntity("service_enabled", enabled.toString()))
    }

    suspend fun getGuardianPhone(): String {
        return settingDao.getSetting("guardian_phone")?.value ?: ""
    }

    suspend fun getGuardianName(): String {
        return settingDao.getSetting("guardian_name")?.value ?: ""
    }

    // Emergency Contacts (up to 5)
    fun getEmergencyContactsFlow(): Flow<List<EmergencyContact>> {
        val flows = (0 until 5).map { index ->
            val nameFlow = settingDao.getSettingFlow("emergency_contact_name_$index")
            val phoneFlow = settingDao.getSettingFlow("emergency_contact_phone_$index")
            nameFlow.combine(phoneFlow) { nameEnt, phoneEnt ->
                EmergencyContact(
                    index = index,
                    name = nameEnt?.value ?: "",
                    phone = phoneEnt?.value ?: ""
                )
            }
        }
        return combine(flows) { array ->
            array.toList()
        }
    }

    suspend fun saveEmergencyContact(index: Int, name: String, phone: String) {
        settingDao.insertSetting(SettingEntity("emergency_contact_name_$index", name))
        settingDao.insertSetting(SettingEntity("emergency_contact_phone_$index", phone))
    }

    suspend fun getEmergencyContactsOnce(): List<EmergencyContact> {
        val list = mutableListOf<EmergencyContact>()
        for (index in 0 until 5) {
            val name = settingDao.getSetting("emergency_contact_name_$index")?.value ?: ""
            val phone = settingDao.getSetting("emergency_contact_phone_$index")?.value ?: ""
            list.add(EmergencyContact(index, name, phone))
        }
        return list
    }

    suspend fun addLog(durationMin: Long, status: String) {
        callLogDao.insertLog(CallLogEntity(timestamp = System.currentTimeMillis(), durationMin = durationMin, status = status))
    }

    suspend fun clearLogs() {
        callLogDao.clearLogs()
    }

    // Home Location and Auto-Ringer features
    val homeLatitudeFlow: Flow<Double> = settingDao.getSettingFlow("home_latitude")
        .map { it?.value?.toDoubleOrNull() ?: 0.0 }

    val homeLongitudeFlow: Flow<Double> = settingDao.getSettingFlow("home_longitude")
        .map { it?.value?.toDoubleOrNull() ?: 0.0 }

    val homeAddressFlow: Flow<String> = settingDao.getSettingFlow("home_address")
        .map { it?.value ?: "" }

    val isHomeAutoRingerEnabledFlow: Flow<Boolean> = settingDao.getSettingFlow("home_auto_ringer_enabled")
        .map { it?.value?.toBoolean() ?: false }

    suspend fun saveHomeLocation(latitude: Double, longitude: Double, address: String) {
        settingDao.insertSetting(SettingEntity("home_latitude", latitude.toString()))
        settingDao.insertSetting(SettingEntity("home_longitude", longitude.toString()))
        settingDao.insertSetting(SettingEntity("home_address", address))
    }

    suspend fun saveHomeAutoRingerEnabled(enabled: Boolean) {
        settingDao.insertSetting(SettingEntity("home_auto_ringer_enabled", enabled.toString()))
    }
}

