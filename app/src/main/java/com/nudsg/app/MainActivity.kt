package com.nudsg.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.UUID

data class PendingAttachment(val name: String,val mime: String,val size: Long,val localPath: String)

class MainActivity : Activity() {
 private lateinit var messagesLayout:LinearLayout
 private lateinit var scroll:ScrollView
 private lateinit var input:EditText
 private lateinit var send:Button
 private lateinit var attachmentStrip:LinearLayout
 private val api=ApiClient()
 private lateinit var store:ChatStore
 private val pendingAttachments=mutableListOf<PendingAttachment>()
 private val prefs by lazy{getSharedPreferences("nudsg",MODE_PRIVATE)}
 private var currentChatId=-1L
 private var endpoint:String
  get()=prefs.getString("api_base_url",BuildConfig.DEFAULT_API_BASE_URL)?:BuildConfig.DEFAULT_API_BASE_URL
  set(v){prefs.edit().putString("api_base_url",v.trim().trimEnd('/')).apply()}
 private val model:String get()=prefs.getString("model","llama3.2")?:"llama3.2"

 override fun onCreate(state:Bundle?){
  super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(window,true)
  window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
  store=ChatStore(this);buildUi();openInitialChat()
 }
 private fun buildUi(){
  val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(16,17,22))}
  val bar=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8))}
  val menu=TextView(this).apply{text="☰";textSize=25f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(10),dp(5),dp(10),dp(5));setOnClickListener{showChatList()}}
  val title=TextView(this).apply{text="NudSG";textSize=21f;setTextColor(Color.WHITE);typeface=Typeface.DEFAULT_BOLD}
  val newChat=TextView(this).apply{text="＋";textSize=25f;setTextColor(Color.WHITE);gravity=Gravity.CENTER;setPadding(dp(10),dp(4),dp(10),dp(4));setOnClickListener{createNewChat()}}
  bar.addView(menu);bar.addView(title,LinearLayout.LayoutParams(0,-2,1f));bar.addView(newChat);bar.addView(smallButton("API"){showEndpointDialog()})
  bar.addView(smallButton("Plugins"){showPluginsDialog()},LinearLayout.LayoutParams(-2,-2).apply{marginStart=dp(5)})
  root.addView(bar)
  scroll=ScrollView(this).apply{isFillViewport=true;clipToPadding=false}
  messagesLayout=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(24))}
  scroll.addView(messagesLayout);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
  val composer=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(4),dp(10),dp(10))}
  attachmentStrip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;visibility=View.GONE}
  composer.addView(attachmentStrip,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(4)})
  val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
  input=EditText(this).apply{hint="Message your local AI…";setHintTextColor(Color.rgb(145,148,160));setTextColor(Color.WHITE);textSize=16f;maxLines=4;setPadding(dp(16),dp(11),dp(16),dp(11));background=rounded(Color.rgb(31,33,41),24);setOnFocusChangeListener{_,f->if(f)scrollToBottomSoon()}}
  val attach=Button(this).apply{text="＋";textSize=22f;setTextColor(Color.WHITE);background=rounded(Color.rgb(38,40,50),22);setOnClickListener{chooseAttachment()}}
  send=Button(this).apply{text="Send";textSize=14f;setTextColor(Color.WHITE);background=rounded(Color.rgb(112,96,220),22);setOnClickListener{sendMessage()}}
  row.addView(input,LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(6)});row.addView(attach,LinearLayout.LayoutParams(dp(52),dp(48)).apply{marginEnd=dp(6)});row.addView(send,LinearLayout.LayoutParams(dp(78),dp(48)})
  composer.addView(row);root.addView(composer,LinearLayout.LayoutParams(-1,-2));setContentView(root)
  ViewCompat.setOnApplyWindowInsetsListener(root){v,i->val b=i.getInsets(WindowInsetsCompat.Type.systemBars());val k=i.getInsets(WindowInsetsCompat.Type.ime());v.setPadding(0,0,0,maxOf(b.bottom,k.bottom));scrollToBottomSoon();i}
  ViewCompat.requestApplyInsets(root)
 }
 private fun openInitialChat(){val chats=store.listChats();if(chats.isEmpty())createNewChat(false) else loadChat(chats.first().id)}
 private fun createNewChat(prompt:Boolean=true){
  if(currentChatId!=-1L&&store.loadMessages(currentChatId).isEmpty())return
  currentChatId=store.createChat();val n=prefs.getInt("chat_create_count",0)+1;prefs.edit().putInt("chat_create_count",n).apply();loadChat(currentChatId)
  if(prompt&&n%5==0)showCleanupPrompt()
 }
 private fun showChatList(){
  val chats=store.listChats()
  val labels=chats.map{it.title.ifBlank{"New chat"}+"\n"+relativeDate(it.lastUsedAt)}.toTypedArray()
  AlertDialog.Builder(this).setTitle("Your chats").setItems(labels){_,w->loadChat(chats[w].id)}.setNeutralButton("New chat"){_,_->createNewChat()}.setPositiveButton("Done",null).show()
 }
 private fun loadChat(id:Long){
  currentChatId=id;store.touchChat(id);messagesLayout.removeAllViews();pendingAttachments.clear();refreshAttachmentStrip()
  store.loadMessages(id).forEach{m->if(m.role=="user")addUserBubble(m.content,m.attachments.map{PendingAttachment(it.name,it.mime,it.size,it.path)}) else addAssistantBubble(m.content,m.status,m.id)}
  scrollToBottomSoon()
 }
 private fun showCleanupPrompt(){
  val choices=arrayOf("Delete chats unused 30 days","Delete chats unused 60 days","Delete chats unused 90 days","Keep everything")
  AlertDialog.Builder(this).setTitle("Chat history cleanup").setMessage("You have created 5 more chats. Would you like to remove old histories to keep storage under control? Saved attachments for deleted chats are removed too.").setItems(choices){_,w->
   if(w<3){val paths=store.deleteUnused((w+1)*30,currentChatId);paths.forEach{File(it).delete()}}
  }.setNegativeButton("Not now",null).show()
 }
 private fun sendMessage(){
  val text=input.text.toString().trim();if(text.isEmpty()&&pendingAttachments.isEmpty())return
  val atts=pendingAttachments.toList();val display=if(atts.isEmpty())text else if(text.isEmpty())"Attached files" else text
  val uid=store.addMessage(currentChatId,"user",display,"SENT");atts.forEach{store.addAttachment(uid,it.name,it.mime,it.size,it.localPath)}
  val chat=store.listChats().firstOrNull{it.id==currentChatId};if(chat?.title=="New chat"&&text.isNotBlank())store.renameChat(currentChatId,text)
  addUserBubble(display,atts);pendingAttachments.clear();refreshAttachmentStrip();input.setText("")
  val aid=store.addMessage(currentChatId,"assistant","","GENERATING");val bubble=addAssistantBubble("","GENERATING",aid);send.isEnabled=false
  api.streamChat(endpoint,model,buildApiHistory(),{chunk->runOnUiThread{
   val next=bubble.text.toString()+chunk;bubble.text=next;store.updateMessage(aid,next,"GENERATING");scrollToBottomSoon()
  }},{runOnUiThread{
   val final=bubble.text.toString();store.updateMessage(aid,final,"COMPLETE");if(final.isBlank())bubble.text="No response returned.";send.isEnabled=true
  }},{err->runOnUiThread{
   val partial=bubble.text.toString();store.updateMessage(aid,partial,"FAILED")
   bubble.text=if(partial.isBlank())"⚠ Generation failed\n"+api.friendlyError(err,endpoint) else partial+"\n\n⚠ Generation stopped: "+api.friendlyError(err,endpoint)
   send.isEnabled=true;scrollToBottomSoon()
  }})
 }
 private fun buildApiHistory():List<ChatMessage> = store.loadMessages(currentChatId).filter{it.content.isNotBlank()&&(it.status!="FAILED"||it.role=="user")}.map{m->ChatMessage(m.role,m.content,m.attachments.filter{it.mime.startsWith("image/")}.mapNotNull{fileToBase64(it.path)})}
 private fun fileToBase64(path:String):String?=try{android.util.Base64.encodeToString(File(path).readBytes(),android.util.Base64.NO_WRAP)}catch(_:Throwable){null}
 private fun chooseAttachment(){startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)},1001)}
 @Deprecated("Android activity result API")
 override fun onActivityResult(req:Int,res:Int,data:Intent?){super.onActivityResult(req,res,data);if(req!=1001||res!=RESULT_OK||data==null)return;val uris=mutableListOf<Uri>();data.clipData?.let{for(i in 0 until it.itemCount)uris.add(it.getItemAt(i).uri)}?:data.data?.let{uris.add(it)};uris.forEach{copyAttachment(it)?.let{a->pendingAttachments.add(a)}};refreshAttachmentStrip()}
 private fun copyAttachment(uri:Uri):PendingAttachment?=try{
  val mime=contentResolver.getType(uri).orEmpty().ifBlank{"application/octet-stream"};val name=queryDisplayName(uri)?:uri.lastPathSegment?:"attachment";val size=querySize(uri)
  val dir=File(filesDir,"attachments").apply{mkdirs()};val out=File(dir,UUID.randomUUID().toString()+"_"+name.replace("[^A-Za-z0-9._-]".toRegex(),"_"))
  contentResolver.openInputStream(uri)?.use{ins->out.outputStream().use{outs->ins.copyTo(outs)}};PendingAttachment(name,mime,size,out.absolutePath)
 }catch(_:Throwable){null}
 private fun refreshAttachmentStrip(){
  attachmentStrip.removeAllViews();if(pendingAttachments.isEmpty()){attachmentStrip.visibility=View.GONE;return};attachmentStrip.visibility=View.VISIBLE
  pendingAttachments.forEachIndexed{i,a->
   val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(5),dp(5),dp(5),dp(5));background=rounded(Color.rgb(31,33,41),12)}
   if(a.mime.startsWith("image/"))card.addView(ImageView(this).apply{setImageBitmap(BitmapFactory.decodeFile(a.localPath));scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(dp(64),dp(64)))
   else card.addView(TextView(this).apply{text=fileEmoji(a.mime);textSize=30f;gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(64),dp(48)))
   card.addView(TextView(this).apply{text=ellipsizeName(a.name);textSize=10f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER},LinearLayout.LayoutParams(dp(80),dp(20)))
   card.setOnClickListener{pendingAttachments.removeAt(i.coerceAtMost(pendingAttachments.lastIndex));refreshAttachmentStrip()}
   attachmentStrip.addView(card,LinearLayout.LayoutParams(dp(88),dp(92)).apply{marginEnd=dp(6)})
  }
 }
 private fun addUserBubble(text:String,atts:List<PendingAttachment>){
  val wrap=LinearLayout(this).apply{gravity=Gravity.END;setPadding(0,dp(5),0,dp(5))}
  val bubble=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(10),dp(10),dp(10),dp(10));background=rounded(Color.rgb(76,67,139),20)}
  val imgs=atts.filter{it.mime.startsWith("image/")};if(imgs.isNotEmpty()){val row=LinearLayout(this);imgs.forEach{a->row.addView(ImageView(this).apply{setImageBitmap(BitmapFactory.decodeFile(a.localPath));scaleType=ImageView.ScaleType.CENTER_CROP},LinearLayout.LayoutParams(dp(120),dp(120)).apply{marginEnd=dp(6)})};bubble.addView(row)}
  atts.filter{!it.mime.startsWith("image/")}.forEach{a->bubble.addView(TextView(this).apply{text=fileEmoji(a.mime)+"  "+a.name+"\n"+formatSize(a.size);textSize=13f;setTextColor(Color.WHITE);setPadding(dp(9),dp(7),dp(9),dp(7));background=rounded(Color.argb(45,255,255,255),10)})}
  if(text.isNotBlank())bubble.addView(TextView(this).apply{this.text=text;textSize=16f;setTextColor(Color.WHITE);setPadding(dp(5),dp(7),dp(5),dp(2))})
  wrap.addView(bubble,LinearLayout.LayoutParams((resources.displayMetrics.widthPixels*.82f).toInt(),-2));messagesLayout.addView(wrap)
 }
 private fun addAssistantBubble(text:String,status:String,id:Long):TextView{
  val wrap=LinearLayout(this).apply{gravity=Gravity.START;setPadding(0,dp(5),0,dp(5))}
  val bubble=TextView(this).apply{this.text=if(status=="FAILED"&&text.isBlank())"⚠ Generation failed" else text;textSize=16f;setTextColor(Color.WHITE);setPadding(dp(15),dp(11),dp(15),dp(11));background=rounded(Color.rgb(31,33,41),20)}
  wrap.addView(bubble,LinearLayout.LayoutParams((resources.displayMetrics.widthPixels*.82f).toInt(),-2));messagesLayout.addView(wrap);return bubble
 }
 private fun showPluginsDialog(){
  val items=arrayOf("GitHub — connect account / token","Google Drive — OAuth connector","Web Links — fetch page context","Image Vision — local model images")
  AlertDialog.Builder(this).setTitle("NudSG Plugins").setItems(items){_,w->when(w){0->showGitHubPluginDialog();1->showSimplePluginInfo("Google Drive","The Drive connector will use OAuth and only access files you authorize.");2->showSimplePluginInfo("Web Links","The web connector will fetch a URL and provide readable page context to the model.");3->showSimplePluginInfo("Image Vision","Images are stored locally and sent as Ollama-compatible image inputs when supported.")}}.setPositiveButton("Done",null).show()
 }
 private fun showGitHubPluginDialog(){
  val box=EditText(this).apply{hint="GitHub token";setSingleLine();setText(prefs.getString("github_token",""))}
  AlertDialog.Builder(this).setTitle("GitHub Plugin").setMessage("GitHub access is account-based; NudSG itself does not charge for this connector. Use only the repository permissions you need.").setView(box).setPositiveButton("Save"){_,_->prefs.edit().putString("github_token",box.text.toString().trim()).apply()}.setNegativeButton("Cancel",null).show()
 }
 private fun showSimplePluginInfo(t:String,m:String)=AlertDialog.Builder(this).setTitle(t).setMessage(m).setPositiveButton("Done",null).show()
 private fun showEndpointDialog(){
  val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(4),dp(22),0)}
  val ep=EditText(this).apply{setText(endpoint);setSingleLine();hint="http://127.0.0.1:11434"};val mo=EditText(this).apply{setText(model);setSingleLine();hint="llama3.2"};box.addView(ep);box.addView(mo)
  AlertDialog.Builder(this).setTitle("Local AI connection").setMessage("127.0.0.1 means this Android device. If Ollama is on another device, use its LAN address.").setView(box).setPositiveButton("Save"){_,_->endpoint=ep.text.toString();prefs.edit().putString("model",mo.text.toString().trim()).apply()}.setNegativeButton("Cancel",null).show()
 }
 private fun smallButton(s:String,click:()->Unit)=TextView(this).apply{text="  "+s+"  ";textSize=12f;setTextColor(Color.LTGRAY);gravity=Gravity.CENTER;setPadding(dp(8),dp(7),dp(8),dp(7));background=rounded(Color.rgb(38,40,50),18);setOnClickListener{click()}}
 private fun queryDisplayName(uri:Uri):String?=contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}
 private fun querySize(uri:Uri):Long=contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.SIZE),null,null,null)?.use{if(it.moveToFirst()&&!it.isNull(0))it.getLong(0)else 0L}?:0L
 private fun fileEmoji(m:String)=when{m.startsWith("image/")->"🖼️";m.contains("pdf")->"📕";m.contains("zip")||m.contains("compressed")->"🗜️";m.contains("json")||m.contains("text")->"📄";m.contains("gltf")||m.contains("blend")||m.contains("octet")->"🧊";else->"📎"}
 private fun ellipsizeName(s:String)=if(s.length<=12)s else s.take(9)+"…"
 private fun formatSize(n:Long)=when{n<=0L->"file";n<1024L->n.toString()+" B";n<1024L*1024L->(n/1024L).toString()+" KB";else->(n/(1024L*1024L)).toString()+" MB"}
 private fun relativeDate(t:Long):String{val d=(System.currentTimeMillis()-t)/86_400_000L;return when{d<=0L->"Today";d==1L->"Yesterday";else->d.toString()+" days ago"}}
 private fun scrollToBottomSoon(){scroll.postDelayed({scroll.fullScroll(View.FOCUS_DOWN)},80)}
 private fun rounded(c:Int,r:Int)=GradientDrawable().apply{setColor(c);cornerRadius=dp(r).toFloat()}
 private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
 override fun onDestroy(){api.shutdown();store.closeStore();super.onDestroy()}
}