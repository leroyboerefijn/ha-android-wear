package io.homeassistant.companion.android.common.sensors

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.math.RoundingMode
import io.homeassistant.companion.android.common.R as commonR

class BatterySensorManager : SensorManager {

    companion object {
        private const val TAG = "BatterySensor"
        private const val SETTING_BATTERY_CURRENT_DIVISOR = "battery_current_divisor"
        private const val DEFAULT_BATTERY_CURRENT_DIVISOR = 1000000
        private val batteryLevel = SensorManager.BasicSensor(
            "battery_level",
            "sensor",
            commonR.string.basic_sensor_name_battery_level,
            commonR.string.sensor_description_battery_level,
            "mdi:battery",
            deviceClass = "battery",
            unitOfMeasurement = "%",
            stateClass = SensorManager.STATE_CLASS_MEASUREMENT,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )
        private val batteryState = SensorManager.BasicSensor(
            "battery_state",
            "sensor",
            commonR.string.basic_sensor_name_battery_state,
            commonR.string.sensor_description_battery_state,
            "mdi:battery-charging",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC,
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )
        val isChargingState = SensorManager.BasicSensor(
            "is_charging",
            "binary_sensor",
            commonR.string.basic_sensor_name_charging,
            commonR.string.sensor_description_charging,
            "mdi:power-plug",
            deviceClass = "plug",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC,
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )
        private val chargerTypeState = SensorManager.BasicSensor(
            "charger_type",
            "sensor",
            commonR.string.basic_sensor_name_charger_type,
            commonR.string.sensor_description_charger_type,
            "mdi:power-plug",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC,
            updateType = SensorManager.BasicSensor.UpdateType.INTENT
        )
        private val batteryHealthState = SensorManager.BasicSensor(
            "battery_health",
            "sensor",
            commonR.string.basic_sensor_name_battery_health,
            commonR.string.sensor_description_battery_health,
            "mdi:battery-heart-variant",
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        private val batteryTemperature = SensorManager.BasicSensor(
            "battery_temperature",
            "sensor",
            commonR.string.basic_sensor_name_battery_temperature,
            commonR.string.sensor_description_battery_temperature,
            "mdi:battery",
            deviceClass = "temperature",
            unitOfMeasurement = "°C",
            stateClass = SensorManager.STATE_CLASS_MEASUREMENT,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        private val batteryPower = SensorManager.BasicSensor(
            "battery_power",
            "sensor",
            commonR.string.basic_sensor_name_battery_power,
            commonR.string.sensor_description_battery_power,
            "mdi:battery-plus",
            "power",
            unitOfMeasurement = "W",
            stateClass = SensorManager.STATE_CLASS_MEASUREMENT,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )

        fun getIsCharging(intent: Intent): Boolean {
            val status: Int = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    override fun docsLink(): String {
        return "https://companion.home-assistant.io/docs/core/sensors#battery-sensors"
    }

    override val enabledByDefault: Boolean
        get() = true

    override val name: Int
        get() = commonR.string.sensor_name_battery

    override suspend fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> {
        return listOf(
            batteryLevel,
            batteryState,
            isChargingState,
            chargerTypeState,
            batteryHealthState,
            batteryTemperature,
            batteryPower
        )
    }

    override fun requiredPermissions(sensorId: String): Array<String> {
        return emptyArray()
    }

    override fun requestSensorUpdate(
        context: Context
    ) {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) {
            updateBatteryLevel(context, intent)
            updateBatteryState(context, intent)
            updateIsCharging(context, intent)
            updateChargerType(context, intent)
            updateBatteryHealth(context, intent)
            updateBatteryTemperature(context, intent)
            updateBatteryPower(context, intent)
        }
    }

    private fun getBatteryPercentage(intent: Intent): Int {
        val level: Int = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale: Int = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return (level.toFloat() / scale.toFloat() * 100.0f).toInt()
    }

    private fun updateBatteryLevel(context: Context, intent: Intent) {
        if (!isEnabled(context, batteryLevel.id))
            return

        val percentage = getBatteryPercentage(intent)
        val baseIcon = when (getChargingStatus(intent)) {
            "charging", "full" -> when (getChargerType(intent)) {
                "wireless" -> "mdi:battery-charging-wireless"
                else -> "mdi:battery-charging"
            }
            else -> "mdi:battery"
        }
        val roundedPercentage = (percentage / 10) * 10
        val icon = when (percentage) {
            in 0..100 -> baseIcon + when (percentage) {
                in 0..9 -> "-outline"
                100 -> ""
                else -> "-$roundedPercentage"
            }
            else -> "mdi:battery-unknown"
        }

        onSensorUpdated(
            context,
            batteryLevel,
            percentage,
            icon,
            mapOf()
        )
    }

    private fun updateBatteryState(context: Context, intent: Intent) {
        if (!isEnabled(context, batteryState.id))
            return

        val chargingStatus = getChargingStatus(intent)

        val icon = when (chargingStatus) {
            "charging" -> "mdi:battery-plus"
            "discharging" -> "mdi:battery-minus"
            "full" -> "mdi:battery-charging"
            "not_charging" -> "mdi:battery"
            else -> "mdi:battery-unknown"
        }
        onSensorUpdated(
            context,
            batteryState,
            chargingStatus,
            icon,
            mapOf()
        )
    }

    private fun updateIsCharging(context: Context, intent: Intent) {
        if (!isEnabled(context, isChargingState.id))
            return

        val isCharging = getIsCharging(intent)

        val icon = if (isCharging) "mdi:power-plug" else "mdi:power-plug-off"
        onSensorUpdated(
            context,
            isChargingState,
            isCharging,
            icon,
            mapOf()
        )
    }

    private fun updateChargerType(context: Context, intent: Intent) {
        if (!isEnabled(context, chargerTypeState.id))
            return

        val chargerType = getChargerType(intent)

        val icon = when (chargerType) {
            "ac" -> "mdi:power-plug"
            "usb" -> "mdi:usb-port"
            "wireless" -> "mdi:battery-charging-wireless"
            else -> "mdi:battery"
        }
        onSensorUpdated(
            context,
            chargerTypeState,
            chargerType,
            icon,
            mapOf()
        )
    }

    private fun updateBatteryHealth(context: Context, intent: Intent) {
        if (!isEnabled(context, batteryHealthState.id))
            return

        val batteryHealth = getBatteryHealth(intent)

        val icon = when (batteryHealth) {
            "good" -> "mdi:battery-heart-variant"
            else -> "mdi:battery-alert"
        }
        onSensorUpdated(
            context,
            batteryHealthState,
            batteryHealth,
            icon,
            mapOf()
        )
    }

    private fun updateBatteryTemperature(context: Context, intent: Intent) {
        if (!isEnabled(context, batteryTemperature.id))
            return

        val batteryTemp = getBatteryTemperature(intent)

        onSensorUpdated(
            context,
            batteryTemperature,
            batteryTemp,
            batteryTemperature.statelessIcon,
            mapOf()
        )
    }

    private fun updateBatteryPower(context: Context, intent: Intent) {
        if (!isEnabled(context, batteryPower.id))
            return

        val voltage = getBatteryVolts(intent)
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val current = getBatteryCurrent(context, batteryManager)
        val wattage = voltage * current
        val icon = if (wattage > 0) batteryPower.statelessIcon else "mdi:battery-minus"

        onSensorUpdated(
            context,
            batteryPower,
            wattage.toBigDecimal().setScale(2, RoundingMode.HALF_UP),
            icon,
            mapOf(
                "current" to current,
                "voltage" to voltage
            )
        )
    }

    private fun getChargerType(intent: Intent): String {
        return when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "ac"
            BatteryManager.BATTERY_PLUGGED_USB -> "usb"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
            else -> "none"
        }
    }

    private fun getChargingStatus(intent: Intent): String {
        return when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
    }

    private fun getBatteryHealth(intent: Intent): String {
        return when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheated"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failed"
            else -> "unknown"
        }
    }

    private fun getBatteryTemperature(intent: Intent): Float {
        return intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
    }

    private fun getBatteryCurrent(context: Context, batteryManager: BatteryManager): Float {
        val dividerSetting = getNumberSetting(
            context,
            batteryPower,
            SETTING_BATTERY_CURRENT_DIVISOR,
            DEFAULT_BATTERY_CURRENT_DIVISOR
        )
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / dividerSetting.toFloat()
    }

    private fun getBatteryVolts(intent: Intent): Float {
        return intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
    }
}
