package com.example.termproject

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.security.MessageDigest
import android.database.sqlite.SQLiteDatabase
import kotlin.math.log

class MainActivity : AppCompatActivity() {
    private val OPEN_FILE_REQUEST_CODE = 1
    private var notes: Notes? = null
    public var openedUri : Uri? = null
    private var fn:String?=null
    var fl:MutableList<String> = mutableListOf()

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val database:SQLiteDatabase?=openOrCreateDatabase("pdffile", MODE_PRIVATE,null)
        val openFileButton: Button = findViewById(R.id.openFileButton)
        val recentopenedRv: RecyclerView =findViewById(R.id.recentOpenedRv)

        notes = Notes(0, emptyMap<Int, ListInfo>().toMutableMap())
        val tableName="files"
        if (database!=null)
        {
            database?.execSQL("create table if not exists ${tableName}"+
                    "( id integer PRIMARY KEY autoincrement, "+
                    "filename text, "+
                    "url text, " +
                    "rodate datetime) ")
        }

        val cursor=database?.rawQuery("select filename,url,rodate "+
                "from ${tableName} "+
                "order by rodate desc",null)
        for(index in 0 until cursor!!.count) {
            cursor.moveToNext()
            val fn = cursor.getString(0)
            fl.add(fn)
        }

        recentopenedRv.layoutManager= LinearLayoutManager(this)
        recentopenedRv.adapter=MyAdapter(fl)
        recentopenedRv.addItemDecoration(
            DividerItemDecoration(
                this, LinearLayoutManager.VERTICAL
            )
        )

        openFileButton.setOnClickListener {
            //database.execSQL("drop table files")
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(intent, OPEN_FILE_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val database:SQLiteDatabase?=openOrCreateDatabase("pdffile", MODE_PRIVATE,null)
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OPEN_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            openedUri = data?.data
            Log.d("asdf","${openedUri}")
            val cursor:Cursor?= openedUri?.let { contentResolver.query(it,null,null,null,null) }
            val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            cursor?.moveToFirst();
            fn= nameIndex?.let { cursor?.getString(it) }
            if(fn in fl){
                database?.rawQuery("update files SET rodate=(select datetime('now','localtime')) where url='${openedUri}'",null)
            }
            else{
                database?.execSQL("insert into files(filename,url,rodate) values"+
                        "('${fn}','${openedUri}',(select datetime('now','localtime')))")
                fl.add(fn.toString())
            }

            openedUri?.let {
                handlePdfFile(it)
                saveJson()
                openNoteViewActivity()
                notes = null
            }
        }
    }

    override fun onResume() {
        super.onResume()
        var fn=""
        val recentopenedRv: RecyclerView =findViewById(R.id.recentOpenedRv)
        val database:SQLiteDatabase?=openOrCreateDatabase("pdffile", MODE_PRIVATE,null)
        fl.clear()
        val cursor=database?.rawQuery("select filename "+
                "from files "+
                "order by rodate desc",null)
        for(index in 0 until cursor!!.count) {
            cursor.moveToNext()
            fn = cursor.getString(0)
            fl.add(fn)
        }

        recentopenedRv.layoutManager= LinearLayoutManager(this)
        recentopenedRv.adapter=MyAdapter(fl)
        recentopenedRv.addItemDecoration(
            DividerItemDecoration(
                this, LinearLayoutManager.VERTICAL
            )
        )
    }

    private fun calculateFileHash(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val buffer = inputStream?.readBytes()
        inputStream?.close()

        val md = MessageDigest.getInstance("MD5")
        val hashBytes = md.digest(buffer)

        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    private fun handlePdfFile(uri: Uri) {
        val fileHash = calculateFileHash(uri)
        if(File(getExternalFilesDir(null), "$fileHash.json").exists()) {
            handleJsonFile(File(getExternalFilesDir(null)
                , "$fileHash.json"
            ).toUri())
            return
        }
        contentResolver.openFileDescriptor(uri, "r")?.use { parcelFileDescriptor ->
            val pdfRenderer = PdfRenderer(parcelFileDescriptor)
            val pageCount = pdfRenderer.pageCount
            notes?.nextPage = pageCount
            for (i in 0 until pageCount) {
                val jsonData = ListInfo(
                    unique = i,
                    nextIndex = -1,
                    prevIndex = if (notes?.noteMap!!.isNotEmpty()) notes?.noteMap!!.size - 1 else -1,
                    keyIndex = -1,
                    tag = 0
                )
                if (notes?.noteMap!!.isNotEmpty()) {
                    notes?.noteMap!![i - 1]?.nextIndex = notes?.noteMap!!.size
                }

                notes?.noteMap!![i] = jsonData
            }
        }
    }

    private fun handleJsonFile(uri: Uri) {
        contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = BufferedReader(InputStreamReader(inputStream))
            val stringBuilder = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                stringBuilder.append(line)
            }
            reader.close()
            inputStream.close()

            val jsonString = stringBuilder.toString()
            notes = gson.fromJson(jsonString, Notes::class.java)
        }
    }

    private fun saveJson() {
        // 파일 이름 및 경로 설정
        val fileHash = calculateFileHash(openedUri!!)
        val fileName = "$fileHash.json"
        val file = File(getExternalFilesDir(null), fileName)
        val json = gson.toJson(notes).replace("\n", "")

        FileOutputStream(file).use {
            it.write(json.toByteArray())
        }
        // 파일 저장 여부 확인
        if (file.exists()) {
            Log.d("MainActivity", "File saved successfully: ${file.absolutePath}")
        } else {
            Log.e("MainActivity", "File not saved")
        }
    }

    private fun openNoteViewActivity() {
        // 파일 이름 및 경로 설정
        val fileHash = calculateFileHash(openedUri!!)
        val fileName = "$fileHash.json"
        val file = File(getExternalFilesDir(null), fileName)

        Log.d("json file open", "File saved successfully: ${file.absolutePath}")

        if (!file.exists()) {
            Log.e("MainActivity", "File not found: ${file.absolutePath}")
            return
        }
        // 파일 URI 가져오기
        val jsonUri = FileProvider.getUriForFile(this, "${applicationContext.packageName}.fileprovider", file)
        val intent = Intent(this, MainNoteViewActivity::class.java).apply {
            putExtra("jsonUri", jsonUri.toString())
            putExtra("pdfUri", openedUri.toString())
            putExtra("fileHash", fileHash)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }
}
