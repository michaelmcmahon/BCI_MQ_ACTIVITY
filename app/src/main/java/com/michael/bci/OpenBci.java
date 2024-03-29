package com.michael.bci;

/*
 * Michael McMahon 28.12.2019
 * OpenBci Class is used by BciService Class - Receiver Thread to convert incoming 24-bit and 16-bit
 * signed integer number format into a more standard 32-bit signed integer.
 * see https://docs.openbci.com/docs/02Cyton/CytonDataFormat#firmware-version-100-2014-to-fall-2016
 * see https://github.com/OpenBCI/OpenBCI_Processing/blob/master/OpenBCI_GUI/OpenBCI_ADS1299.pde
 */
class OpenBci {

    /* reference voltage for ADC in ADS1299 set by its hardware */
    private static final float ADS1299_Vref = 4.5f;
    /* OpenBCI board sets the ADS1299 chip to its maximum gain (24x) */
    private static final float ADS1299_gain = 24;

    /*
    Scale factor converts the EEG values from “counts” (int32 number) into scientific units (volts).
    The equation for determining the scale factor is: Scale Factor (Volts/count) = 4.5 Volts / gain / (2^23 - 1);
    ADS1299 datasheet Table 7, confirmed through experiment
     */
    private static final float scale_fac_uVolts_per_count = ADS1299_Vref / ((float)(Math.pow(2,23)-1)) / ADS1299_gain  * 1000000.f;
    /*
    Accelerometer data must also be scaled before it can be correctly interpreted.  The equation used to
    scale Accelerometer data is as follows (We assume 4Gs, so 2mG per digit)
    assume set to +/4G, so 2 mG per digit (datasheet). Account for 4 bits unused
     */
    public static final float scale_fac_accel_G_per_count = 0.002f / ((float)Math.pow(2,4));




    /*
    EEG data values are transferring as a 24-bit signed integer which is the native format
    for the ADS1299 chip, processing code converts this into a standard 32-bit signed integer.
    this function is passed a 3 byte array
     */
    private static int interpret24bitAsInt32(byte[] byteArray) {
        int newInt = (
                ((0xFF & byteArray[0]) << 16) |
                ((0xFF & byteArray[1]) << 8) |
                 (0xFF & byteArray[2])
        );
        if ((newInt & 0x00800000) > 0) {
            newInt |= 0xFF000000;
        } else {
            newInt &= 0x00FFFFFF;
        }
        return newInt;
    }

    /*
    The accelerometer data is sent as a 16bit signed value.
    We're using a similar scheme to convert these values into 32bit integers in Processing.
    this function is passed a 2 byte array
     */
    public static int interpret16bitAsInt32(byte[] byteArray) {
        int newInt = (
                ((0xFF & byteArray[0]) << 8) |
                        (0xFF & byteArray[1])
        );
        if ((newInt & 0x00008000) > 0) {
            newInt |= 0xFFFF0000;
        } else {
            newInt &= 0x0000FFFF;
        }
        return newInt;
    }

    /* Apply the scale factor to the EEG "counts" */
    public static float convertByteToMicroVolts(byte[] byteArray){
        return scale_fac_uVolts_per_count * interpret24bitAsInt32(byteArray);
    }

    /* Apply the scale factor to Accelerometer */
    public static float convertAccelData(byte[] byteArray){
        return scale_fac_accel_G_per_count * interpret16bitAsInt32(byteArray);
    }

}
