/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import android.util.Log;

import com.zoffcc.applications.sorm.Column;
import com.zoffcc.applications.sorm.PrimaryKey;
import com.zoffcc.applications.sorm.Table;

import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.Socket;
import java.util.Iterator;

import static com.zoffcc.applications.trifa.HelperGeneric.validate_ipv4;
import static com.zoffcc.applications.trifa.MainActivity.PREF__orbot_enabled;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOX_NODELIST_HOST;
import static com.zoffcc.applications.trifa.TRIFAGlobals.bootstrap_node_list;
import static com.zoffcc.applications.trifa.TRIFAGlobals.tcprelay_node_list;
import static com.zoffcc.applications.trifa.TorHelper.TorResolve;
import static com.zoffcc.applications.trifa.TorHelper.TorSocket;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

@Table
public class BootstrapNodeEntryDB extends com.zoffcc.applications.sorm.BootstrapNodeEntryDB
{
    static final String TAG = "trifa.BtpNodeEDB";

    @PrimaryKey(autoincrement = true, auto = true)
    long id;

    @Column(indexed = true, helpers = Column.Helpers.ALL)
    long num;

    @Column(indexed = true, helpers = Column.Helpers.ALL)
    boolean udp_node; // true -> UDP bootstrap node, false -> TCP relay node

    @Column(indexed = true, helpers = Column.Helpers.ALL)
    String ip;

    @Column(indexed = true, helpers = Column.Helpers.ALL)
    long port;

    @Column(indexed = true, helpers = Column.Helpers.ALL)
    String key_hex;

    // ______@@SORMA_END@@______

    @Override
    public String toString()
    {
        // return "" + num + ":" + ip + " port=" + port + " udp_node=" + udp_node + " key_hex=" + key_hex;
        // return "" + num + ":" + ip + " port=" + port + " udp_node="+  udp_node;
        return "" + num + ":" + ip + " port=" + port + " udp_node=" + udp_node + "\n";
    }

    public long get_port()
    {
        return port;
    }

    public String get_ip()
    {
        return ip;
    }

    static void insert_node_into_db_real(com.zoffcc.applications.sorm.BootstrapNodeEntryDB n)
    {
        if (orma == null)
        {
            return;
        }
        try
        {
            orma.insertIntoBootstrapNodeEntryDB(n);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void insert_default_udp_nodes_into_db()
    {
        com.zoffcc.applications.sorm.BootstrapNodeEntryDB n;
        int num_ = 0;
        // @formatter:off
        n = BootstrapNodeEntryDB_(true, num_, "bootstrap1.khandaq.org",33445,"74AE9E62A2AE51983CF9C6B526CD89ABD8AA91864B35FC0CF7AC60454CBDDD6D");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "bootstrap2.khandaq.org",33445,"5C6F3903FB1EC4AC386843D8FB584CC34567E045EC26939A6034C3A2746A9B6B");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "bootstrap3.khandaq.org",33445,"A181DD1F8C9A9D41BE1875A5C2687A89C3CB4F0F76ED9C390E7270B01BF24665");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "144.217.167.73",33445,"7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox.abilinski.com",33445,"10C00EB250C3233E343E2AEBA07115A5C28920E9C8D29492F6D00B29049EDC7E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "205.185.115.131",53,"3091C6BEB2A993F1C6300C16549FABA67098FF3D62C6D253828B531470B53D68");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox1.mf-net.eu",33445,"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox1.mf-net.eu",33445,"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "3.0.24.15",33445,"E20ABCF38CDBFFD7D04B29C956B33F7B27A3BB7AF0618101617B036E4AEA402D");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "139.162.110.188",33445,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "2400:8902::f03c:93ff:fe69:bf77",33445,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox2.mf-net.eu",33445,"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox2.mf-net.eu",33445,"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "172.105.109.31",33445,"D46E97CF995DC1820B92B7D899E152A217D36ABE22730FEA4B6BF1BFC06C617C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "2600:3c04::f03c:92ff:fe30:5df",33445,"D46E97CF995DC1820B92B7D899E152A217D36ABE22730FEA4B6BF1BFC06C617C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "91.146.66.26",33445,"B5E7DAC610DBDE55F359C7F8690B294C8E4FCEC4385DE9525DBFA5523EAD9D53");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "172.104.215.182",33445,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "2600:3c03::f03c:93ff:fe7f:6096",33445,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox.initramfs.io",33445,"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox.initramfs.io",33445,"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox3.mf-net.eu",33445,"F4FC9398B7167668ED2BCF85634E04D4CDCDD2F95DA5F305BD234888B6E6A771");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox3.mf-net.eu",33445,"F4FC9398B7167668ED2BCF85634E04D4CDCDD2F95DA5F305BD234888B6E6A771");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "188.214.122.30",33445,"2A9F7A620581D5D1B09B004624559211C5ED3D1D712E8066ACDB0896A7335705");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "43.198.227.166",33445,"AD13AB0D434BCE6C83FE2649237183964AE3341D0AFB3BE1694B18505E4E135E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "95.181.230.108",33445,"B5FFECB4E4C26409EBB88DB35793E7B39BFA3BA12AC04C096950CB842E3E130A");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "2a03:c980:db:5d::",33445,"B5FFECB4E4C26409EBB88DB35793E7B39BFA3BA12AC04C096950CB842E3E130A");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox.hidemybits.com",443,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox.hidemybits.com",443,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox4.mf-net.eu",33445,"DCD342A0D5E2AA8E35C2BD2C7988F906EEB631B35100170A7F30E77D7F596442");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "tox4.mf-net.eu",33445,"DCD342A0D5E2AA8E35C2BD2C7988F906EEB631B35100170A7F30E77D7F596442");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "188.245.84.166",33445,"96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "86.107.187.54",33445,"2C0F90965134C7BEFAFE72B077A19221628D7045BB51C1165A2C75CDB2B32634");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "2a00:bba0:1204:3700:21e:6ff:fe4a:60fc",33445,"2C0F90965134C7BEFAFE72B077A19221628D7045BB51C1165A2C75CDB2B32634");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "119.59.101.63",33445,"197F746696062FA3BD07BB3BC0656ABD6692B4DAA27DACF0F474754F2B09B060");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(true, num_, "167.17.40.142",33445,"E84453123B44A47120FFB469CBCDEEF078D3785D7AD7F6C5B2351CB5DDE2C54C");insert_node_into_db_real(n);num_++;
        // @formatter:on
    }

    public static void insert_default_tcprelay_nodes_into_db()
    {
        com.zoffcc.applications.sorm.BootstrapNodeEntryDB n;
        int num_ = 0;
        // @formatter:off
        n = BootstrapNodeEntryDB_(false, num_, "bootstrap1.khandaq.org",3389,"74AE9E62A2AE51983CF9C6B526CD89ABD8AA91864B35FC0CF7AC60454CBDDD6D");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "bootstrap1.khandaq.org",33445,"74AE9E62A2AE51983CF9C6B526CD89ABD8AA91864B35FC0CF7AC60454CBDDD6D");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "bootstrap2.khandaq.org",3389,"5C6F3903FB1EC4AC386843D8FB584CC34567E045EC26939A6034C3A2746A9B6B");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "bootstrap2.khandaq.org",33445,"5C6F3903FB1EC4AC386843D8FB584CC34567E045EC26939A6034C3A2746A9B6B");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "bootstrap3.khandaq.org",3389,"A181DD1F8C9A9D41BE1875A5C2687A89C3CB4F0F76ED9C390E7270B01BF24665");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "bootstrap3.khandaq.org",33445,"A181DD1F8C9A9D41BE1875A5C2687A89C3CB4F0F76ED9C390E7270B01BF24665");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "144.217.167.73",3389,"7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "144.217.167.73",33445,"7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.abilinski.com",33445,"10C00EB250C3233E343E2AEBA07115A5C28920E9C8D29492F6D00B29049EDC7E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "205.185.115.131",53,"3091C6BEB2A993F1C6300C16549FABA67098FF3D62C6D253828B531470B53D68");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "205.185.115.131",443,"3091C6BEB2A993F1C6300C16549FABA67098FF3D62C6D253828B531470B53D68");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "205.185.115.131",3389,"3091C6BEB2A993F1C6300C16549FABA67098FF3D62C6D253828B531470B53D68");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "205.185.115.131",33445,"3091C6BEB2A993F1C6300C16549FABA67098FF3D62C6D253828B531470B53D68");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox1.mf-net.eu",3389,"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox1.mf-net.eu",33445,"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox1.mf-net.eu",3389,"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox1.mf-net.eu",33445,"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "3.0.24.15",33445,"E20ABCF38CDBFFD7D04B29C956B33F7B27A3BB7AF0618101617B036E4AEA402D");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "139.162.110.188",443,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "139.162.110.188",3389,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "139.162.110.188",33445,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2400:8902::f03c:93ff:fe69:bf77",443,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2400:8902::f03c:93ff:fe69:bf77",3389,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2400:8902::f03c:93ff:fe69:bf77",33445,"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox2.mf-net.eu",3389,"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox2.mf-net.eu",33445,"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox2.mf-net.eu",3389,"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox2.mf-net.eu",33445,"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "172.105.109.31",33445,"D46E97CF995DC1820B92B7D899E152A217D36ABE22730FEA4B6BF1BFC06C617C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2600:3c04::f03c:92ff:fe30:5df",33445,"D46E97CF995DC1820B92B7D899E152A217D36ABE22730FEA4B6BF1BFC06C617C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "91.146.66.26",33445,"B5E7DAC610DBDE55F359C7F8690B294C8E4FCEC4385DE9525DBFA5523EAD9D53");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "172.104.215.182",443,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "172.104.215.182",3389,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "172.104.215.182",33445,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2600:3c03::f03c:93ff:fe7f:6096",443,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2600:3c03::f03c:93ff:fe7f:6096",3389,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2600:3c03::f03c:93ff:fe7f:6096",33445,"DA2BD927E01CD05EBCC2574EBE5BEBB10FF59AE0B2105A7D1E2B40E49BB20239");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.initramfs.io",3389,"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.initramfs.io",33445,"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.initramfs.io",3389,"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.initramfs.io",33445,"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox3.mf-net.eu",3389,"F4FC9398B7167668ED2BCF85634E04D4CDCDD2F95DA5F305BD234888B6E6A771");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox3.mf-net.eu",33445,"F4FC9398B7167668ED2BCF85634E04D4CDCDD2F95DA5F305BD234888B6E6A771");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox3.mf-net.eu",3389,"F4FC9398B7167668ED2BCF85634E04D4CDCDD2F95DA5F305BD234888B6E6A771");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox3.mf-net.eu",33445,"F4FC9398B7167668ED2BCF85634E04D4CDCDD2F95DA5F305BD234888B6E6A771");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "188.214.122.30",3389,"2A9F7A620581D5D1B09B004624559211C5ED3D1D712E8066ACDB0896A7335705");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "188.214.122.30",33445,"2A9F7A620581D5D1B09B004624559211C5ED3D1D712E8066ACDB0896A7335705");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "43.198.227.166",3389,"AD13AB0D434BCE6C83FE2649237183964AE3341D0AFB3BE1694B18505E4E135E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "43.198.227.166",33445,"AD13AB0D434BCE6C83FE2649237183964AE3341D0AFB3BE1694B18505E4E135E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "95.181.230.108",3389,"B5FFECB4E4C26409EBB88DB35793E7B39BFA3BA12AC04C096950CB842E3E130A");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "95.181.230.108",33445,"B5FFECB4E4C26409EBB88DB35793E7B39BFA3BA12AC04C096950CB842E3E130A");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2a03:c980:db:5d::",3389,"B5FFECB4E4C26409EBB88DB35793E7B39BFA3BA12AC04C096950CB842E3E130A");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2a03:c980:db:5d::",33445,"B5FFECB4E4C26409EBB88DB35793E7B39BFA3BA12AC04C096950CB842E3E130A");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.hidemybits.com",443,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.hidemybits.com",3389,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.hidemybits.com",33445,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.hidemybits.com",443,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.hidemybits.com",3389,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox.hidemybits.com",33445,"5D57B95EE4A7F37BA031DAD0CBD9510A9C96FFE09C1CE24A9C33746F39817D6E");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox4.mf-net.eu",3389,"DCD342A0D5E2AA8E35C2BD2C7988F906EEB631B35100170A7F30E77D7F596442");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox4.mf-net.eu",33445,"DCD342A0D5E2AA8E35C2BD2C7988F906EEB631B35100170A7F30E77D7F596442");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox4.mf-net.eu",3389,"DCD342A0D5E2AA8E35C2BD2C7988F906EEB631B35100170A7F30E77D7F596442");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "tox4.mf-net.eu",33445,"DCD342A0D5E2AA8E35C2BD2C7988F906EEB631B35100170A7F30E77D7F596442");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "188.245.84.166",443,"96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "188.245.84.166",3389,"96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "188.245.84.166",33445,"96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "86.107.187.54",3389,"2C0F90965134C7BEFAFE72B077A19221628D7045BB51C1165A2C75CDB2B32634");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "86.107.187.54",33445,"2C0F90965134C7BEFAFE72B077A19221628D7045BB51C1165A2C75CDB2B32634");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2a00:bba0:1204:3700:21e:6ff:fe4a:60fc",3389,"2C0F90965134C7BEFAFE72B077A19221628D7045BB51C1165A2C75CDB2B32634");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "2a00:bba0:1204:3700:21e:6ff:fe4a:60fc",33445,"2C0F90965134C7BEFAFE72B077A19221628D7045BB51C1165A2C75CDB2B32634");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "119.59.101.63",443,"197F746696062FA3BD07BB3BC0656ABD6692B4DAA27DACF0F474754F2B09B060");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "119.59.101.63",33445,"197F746696062FA3BD07BB3BC0656ABD6692B4DAA27DACF0F474754F2B09B060");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "167.17.40.142",3389,"E84453123B44A47120FFB469CBCDEEF078D3785D7AD7F6C5B2351CB5DDE2C54C");insert_node_into_db_real(n);num_++;
        n = BootstrapNodeEntryDB_(false, num_, "167.17.40.142",33445,"E84453123B44A47120FFB469CBCDEEF078D3785D7AD7F6C5B2351CB5DDE2C54C");insert_node_into_db_real(n);num_++;
        // @formatter:on
    }

    public static com.zoffcc.applications.sorm.BootstrapNodeEntryDB BootstrapNodeEntryDB_(boolean udp_node_, int num_, String ip_, long port_, String key_hex_)
    {
        com.zoffcc.applications.sorm.BootstrapNodeEntryDB n = new com.zoffcc.applications.sorm.BootstrapNodeEntryDB();
        n.num = num_;
        n.udp_node = udp_node_;
        n.ip = ip_;
        n.port = port_;
        n.key_hex = key_hex_;

        return n;
    }

    public static String dns_lookup_via_tor(String host_or_ip)
    {
        try
        {
            if (host_or_ip.equals("127.0.0.1"))
            {
                Log.i(TAG, "dns_lookup_via_tor:TorResolve:" + host_or_ip + " == 127.0.0.1");
                return "127.0.0.1";
            }
            else if (validate_ipv4(host_or_ip))
            {
                Log.i(TAG, "dns_lookup_via_tor:TorResolve:" + host_or_ip + " is already an IPv4 address");
                return host_or_ip;
            }

            // TODO: TorResolve can NOT resolve IPv6 address like its written now
            String IP_address = TorResolve(host_or_ip);
            Log.i(TAG, "dns_lookup_via_tor:TorResolve:" + host_or_ip + " -> " + IP_address);

            if ((IP_address == null) || (IP_address.equals("")))
            {
                // if there is some error, use localhost -> which kind of disables this host
                Log.i(TAG, "dns_lookup_via_tor:EE2:IP_address=" + IP_address);
                return "127.0.0.1";
            }
            else
            {
                return IP_address;
            }
        }
        catch (Exception e)
        {
            // if there is some error, use localhost -> which kind of disables this host
            e.printStackTrace();
            Log.i(TAG, "dns_lookup_via_tor:EE1:" + e.getMessage());
            return "127.0.0.1";
        }
    }

    public static void get_tcprelay_nodelist_from_db()
    {
        tcprelay_node_list.clear();

        long tcprelay_nodelist_count = 0;
        try
        {
            tcprelay_nodelist_count = orma.selectFromBootstrapNodeEntryDB().
                    udp_nodeEq(false).count();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        Log.i(TAG, "get_tcprelay_nodelist_from_db:tcprelay_nodelist_count=" + tcprelay_nodelist_count);

        if (tcprelay_nodelist_count == 0)
        {
            Log.i(TAG, "get_tcprelay_nodelist_from_db:insert_default_tcprelay_nodes_into_db");
            insert_default_tcprelay_nodes_into_db();
        }

        // fill tcprelay_node_list with values from DB -----------------
        try
        {
            tcprelay_node_list.addAll(orma.selectFromBootstrapNodeEntryDB().udp_nodeEq(false).orderByNumAsc().toList());
            Log.i(TAG, "get_tcprelay_nodelist_from_db:tcprelay_node_list.addAll " + tcprelay_node_list);

            if (PREF__orbot_enabled)
            {
                Iterator i = bootstrap_node_list.iterator();
                com.zoffcc.applications.sorm.BootstrapNodeEntryDB e2;
                while (i.hasNext())
                {
                    e2 = (com.zoffcc.applications.sorm.BootstrapNodeEntryDB) i.next();
                    e2.ip = dns_lookup_via_tor(e2.ip);

                }
            }
        }
        catch (Exception e)
        {
        }
        // fill tcprelay_node_list with values from DB -----------------
    }

    public static void get_udp_nodelist_from_db()
    {
        bootstrap_node_list.clear();

        long udp_nodelist_count = 0;
        try
        {
            udp_nodelist_count = orma.selectFromBootstrapNodeEntryDB().
                    udp_nodeEq(true).count();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        Log.i(TAG, "get_udp_nodelist_from_db:udp_nodelist_count=" + udp_nodelist_count);

        if (udp_nodelist_count == 0)
        {
            Log.i(TAG, "get_udp_nodelist_from_db:insert_default_udp_nodes_into_db");
            insert_default_udp_nodes_into_db();
        }

        // fill bootstrap_node_list with values from DB -----------------
        try
        {
            bootstrap_node_list.addAll(orma.selectFromBootstrapNodeEntryDB().udp_nodeEq(true).orderByNumAsc().toList());
            Log.i(TAG, "get_udp_nodelist_from_db:bootstrap_node_list.addAll");

            if (PREF__orbot_enabled)
            {
                Iterator i = bootstrap_node_list.iterator();
                com.zoffcc.applications.sorm.BootstrapNodeEntryDB e2;
                while (i.hasNext())
                {
                    e2 = (com.zoffcc.applications.sorm.BootstrapNodeEntryDB) i.next();
                    e2.ip = dns_lookup_via_tor(e2.ip);
                }
            }
        }
        catch (Exception e)
        {
        }
        // fill bootstrap_node_list with values from DB -----------------
    }
}
