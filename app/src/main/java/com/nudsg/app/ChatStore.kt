package com.nudsg.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SavedChat(val id: Long, val title: String, val createdAt: Long, val lastUsedAt: Long)
data class SavedAttachment(val id: Long, val messageId: Long, val name: String, val mime: String, val size: Long, val path: String)
data class SavedMessage(val id: Long, val chatId: Long, val role: String, val content: String, val status: String, val createdAt: Long, val attachments: List<SavedAttachment> = emptyList())

class ChatStore(context: Context) : SQLiteOpenHelper(context, "nudsg_chats.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE chats(id INTEGER PRIMARY KEY AUTOINCREMENT,title TEXT NOT NULL,created_at INTEGER NOT NULL,last_used_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages(id INTEGER PRIMARY KEY AUTOINCREMENT,chat_id INTEGER NOT NULL,role TEXT NOT NULL,content TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE attachments(id INTEGER PRIMARY KEY AUTOINCREMENT,message_id INTEGER NOT NULL,name TEXT NOT NULL,mime TEXT NOT NULL,size INTEGER NOT NULL,path TEXT NOT NULL)")
        db.execSQL("CREATE INDEX idx_messages_chat ON messages(chat_id,created_at)")
        db.execSQL("CREATE INDEX idx_attachments_message ON attachments(message_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun createChat(title: String = "New chat"): Long {
        val now = System.currentTimeMillis()
        return writableDatabase.insert("chats", null, ContentValues().apply { put("title", title); put("created_at", now); put("last_used_at", now) })
    }
    fun listChats(): List<SavedChat> = readableDatabase.rawQuery("SELECT id,title,created_at,last_used_at FROM chats ORDER BY last_used_at DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(SavedChat(c.getLong(0), c.getString(1), c.getLong(2), c.getLong(3))) }
    }
    fun touchChat(id: Long) = writableDatabase.execSQL("UPDATE chats SET last_used_at=? WHERE id=?", arrayOf(System.currentTimeMillis(), id))
    fun renameChat(id: Long, title: String) = writableDatabase.execSQL("UPDATE chats SET title=? WHERE id=?", arrayOf(title.take(80), id))
    fun addMessage(chatId: Long, role: String, content: String, status: String): Long {
        val id = writableDatabase.insert("messages", null, ContentValues().apply { put("chat_id",chatId); put("role",role); put("content",content); put("status",status); put("created_at",System.currentTimeMillis()) })
        touchChat(chatId); return id
    }
    fun updateMessage(id: Long, content: String, status: String) = writableDatabase.execSQL("UPDATE messages SET content=?,status=? WHERE id=?", arrayOf(content,status,id))
    fun addAttachment(messageId: Long, name: String, mime: String, size: Long, path: String) = writableDatabase.insert("attachments", null, ContentValues().apply { put("message_id",messageId); put("name",name); put("mime",mime); put("size",size); put("path",path) })
    fun loadMessages(chatId: Long): List<SavedMessage> {
        val rows = mutableListOf<SavedMessage>()
        readableDatabase.rawQuery("SELECT id,chat_id,role,content,status,created_at FROM messages WHERE chat_id=? ORDER BY created_at,id", arrayOf(chatId.toString())).use { c ->
            while (c.moveToNext()) rows.add(SavedMessage(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getString(4),c.getLong(5)))
        }
        return rows.map { it.copy(attachments = loadAttachments(it.id)) }
    }
    private fun loadAttachments(messageId: Long): List<SavedAttachment> = readableDatabase.rawQuery("SELECT id,message_id,name,mime,size,path FROM attachments WHERE message_id=? ORDER BY id", arrayOf(messageId.toString())).use { c ->
        buildList { while (c.moveToNext()) add(SavedAttachment(c.getLong(0),c.getLong(1),c.getString(2),c.getString(3),c.getLong(4),c.getString(5))) }
    }
    fun deleteChat(id: Long): List<String> {
        val paths = attachmentPaths("SELECT a.path FROM attachments a JOIN messages m ON a.message_id=m.id WHERE m.chat_id=?", arrayOf(id.toString()))
        writableDatabase.beginTransaction()
        try { writableDatabase.delete("messages","chat_id=?",arrayOf(id.toString())); writableDatabase.delete("chats","id=?",arrayOf(id.toString())); writableDatabase.setTransactionSuccessful() } finally { writableDatabase.endTransaction() }
        return paths
    }
    fun deleteUnused(days: Int, keepId: Long): List<String> {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val ids = mutableListOf<Long>()
        readableDatabase.rawQuery("SELECT id FROM chats WHERE last_used_at<? AND id<>?",arrayOf(cutoff.toString(),keepId.toString())).use { c -> while(c.moveToNext()) ids.add(c.getLong(0)) }
        return ids.flatMap { deleteChat(it) }
    }
    private fun attachmentPaths(sql: String,args:Array<String>):List<String> = readableDatabase.rawQuery(sql,args).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
    fun closeStore() = close()
}