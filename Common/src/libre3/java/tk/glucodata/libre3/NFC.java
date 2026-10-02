/*      This file is part of Juggluco, an Android app to receive and display         */
/*      glucose values from Freestyle Libre 2 and 3 sensors.                         */
/*                                                                                   */
/*      Copyright (C) 2021 Jaap Korthals Altes <jaapkorthalsaltes@gmail.com>         */
/*                                                                                   */
/*      Juggluco is free software: you can redistribute it and/or modify             */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Juggluco is distributed in the hope that it will be useful, but              */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Juggluco. If not, see <https://www.gnu.org/licenses/>.            */
/*                                                                                   */
/*      Fri Jan 27 15:27:34 CET 2023                                                 */

package tk.glucodata.libre3;

import static tk.glucodata.Log.doLog;
import static tk.glucodata.Natives.getlibreAccountIDnumber;
import static tk.glucodata.Natives.interpret3NFC2;
import static tk.glucodata.Log.showbytes;

import android.nfc.Tag;

import tk.glucodata.AlgNfcV;
import tk.glucodata.Log;
import tk.glucodata.Natives;


public class NFC {

private static final String LOG_ID="NFC";
public static	long   	second(byte[] nfc1,Tag tag,tk.glucodata.GlucoseCurve curve) {
	long nowsec=(long)Math.round(System.currentTimeMillis()/1000.0);
    final long manual=Natives.manualLibreAccountIDnumber();
    final int lingoVersion=Natives.lingoPatchSecurityVersion(nfc1);
    if(lingoVersion!=0) {
        // The independent Lingo credentials support security version 3 only.
        if(lingoVersion!=3) {
            Log.e(LOG_ID,"Unsupported Lingo security version or invalid patch info: "+lingoVersion);
            return 0L;
            }
        return secondWithID(nfc1,tag,curve,nowsec,manual!=-1L?manual:1001L);
        }
    if(manual!=-1L)  
        return secondWithID(nfc1,tag,curve,nowsec,manual);
	final boolean activate=nfc1[17]==1;
	final boolean newFirst=activate?Natives.getnewLibre3Activation():Natives.getnewLibre3Takeover();
	final long oldID=getlibreAccountIDnumber();
	final long newID=Natives.getnewLibre3ReceiverID();
	final boolean haveNew=newID>=0L && newID<=0xffffffffL;
	if(activate && newFirst && !haveNew) {
		Log.e(LOG_ID,"New Libre 3 receiver ID has not been retrieved");
		return 1L;
		}
	final long firstID=newFirst && haveNew?newID:oldID;
	long result=secondWithID(nfc1,tag,curve,nowsec,firstID);
	// interpret3NFC2 returns 1 for the B1 account-ID rejection, not a stream pointer.
	if(!activate && result==1L && haveNew && newID!=oldID) {
		long otherID=firstID==newID?oldID:newID;
		result=secondWithID(nfc1,tag,curve,nowsec,otherID);
		}
	return result;
	}
private static long secondWithID(byte[] nfc1,Tag tag,tk.glucodata.GlucoseCurve curve,long nowsec,long accountId) {
	{if(doLog) {Log.i(LOG_ID,"accountId="+accountId);};};
	byte[] metcrc=new byte[10];// 8C 42 86 62 8D 6D 41 1F BC 93
	if(Natives.startTimeIDsum(metcrc, nowsec, accountId) != 0) {
		Log.e(LOG_ID, "startTimeIDsum failed");
		return 0L;
		}
	{if(doLog){showbytes("After startTimeIDsum",metcrc);};}
	final byte[] secstart={(byte)0x02,nfc1[17]==1?(byte)0xA0:(byte)0xA8,(byte)0x7A};
//		02 A8 7A
//activate	02 A0 7A
	byte[] command = new byte[secstart.length + metcrc.length];
        System.arraycopy(secstart, 0, command, 0, secstart.length);
        System.arraycopy(metcrc, 0, command, secstart.length, metcrc.length);
	{if(doLog){showbytes("NFC command2: ",command);};}
        var second= AlgNfcV.wholenfccmd(tag,command);
        long[] uit={0L};
	curve.render.sensorid=interpret3NFC2(nfc1,second,nowsec,uit);
        return uit[0];
	}

}
