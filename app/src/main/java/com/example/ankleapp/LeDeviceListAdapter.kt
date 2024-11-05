import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView

class LeDeviceListAdapter(context: Context) : ArrayAdapter<BluetoothDevice>(context, 0) {

    private val devices = mutableListOf<BluetoothDevice>()

    fun addDevice(device: BluetoothDevice) {
        if (!devices.contains(device)) {
            devices.add(device)
            notifyDataSetChanged()
        }
    }

    fun containsDevice(device: BluetoothDevice): Boolean {
        return devices.contains(device)
    }

    override fun getCount(): Int {
        return devices.size
    }

    override fun getItem(position: Int): BluetoothDevice? {
        return devices[position]
    }

    @SuppressLint("MissingPermission")
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_2, parent, false)
        val device = getItem(position)

        val deviceName: TextView = view.findViewById(android.R.id.text1)
        val deviceAddress: TextView = view.findViewById(android.R.id.text2)

        deviceName.text = device?.name ?: "Unknown Device"
        deviceAddress.text = device?.address

        return view
    }

    override fun clear() {
        devices.clear()
        notifyDataSetChanged()
    }
}
