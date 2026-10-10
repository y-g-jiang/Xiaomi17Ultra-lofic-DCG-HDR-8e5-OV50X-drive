#include <stdint.h>
#include <jni.h>
typedef struct ANativeWindow ANativeWindow;
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct { uint32_t width,height,layers,format; uint64_t usage; uint32_t stride,rfu0; uint64_t rfu1; } AHardwareBuffer_Desc;
extern ANativeWindow *ANativeWindow_fromSurface(JNIEnv *,jobject);
extern void ANativeWindow_release(ANativeWindow *);
extern int32_t ANativeWindow_setBuffersGeometry(ANativeWindow *,int32_t,int32_t,int32_t);
extern int32_t ANativeWindow_setBuffersDataSpace(ANativeWindow *,int32_t);
extern int32_t ANativeWindow_getFormat(ANativeWindow *);
extern AHardwareBuffer *AHardwareBuffer_fromHardwareBuffer(JNIEnv *,jobject);
extern void AHardwareBuffer_describe(const AHardwareBuffer *,AHardwareBuffer_Desc *);
extern int AHardwareBuffer_lock(AHardwareBuffer *,uint64_t,int32_t,const void *,void **);
extern int AHardwareBuffer_unlock(AHardwareBuffer *,int32_t *);
JNIEXPORT jbyteArray JNICALL Java_local_jc_mainraw_NativeRawSurfaceProbe_copyRaw14(
 JNIEnv *env,jclass cls,jobject hardware) {
 (void)cls; if(!hardware) return 0;
 AHardwareBuffer *buffer=AHardwareBuffer_fromHardwareBuffer(env,hardware);
 if(!buffer) return 0;
 AHardwareBuffer_Desc d; AHardwareBuffer_describe(buffer,&d);
 if(d.width!=4096 || d.height!=3072 || d.layers!=1 || d.format!=324 || d.stride!=7168) return 0;
 void *pixels=0;
 if(AHardwareBuffer_lock(buffer,3,-1,0,&pixels)!=0) return 0;
 jbyteArray out=0;
 if(pixels) {
   out=(*env)->NewByteArray(env,22020096);
   if(out) (*env)->SetByteArrayRegion(env,out,0,22020096,(const jbyte *)pixels);
 }
 int status=AHardwareBuffer_unlock(buffer,0);
 return status==0 ? out : 0;
}
JNIEXPORT void JNICALL Java_local_jc_mainraw_NativeRawSurfaceProbe_reinitialize(
 JNIEnv *env,jclass cls,jobject reader) {
 (void)cls; if(!reader) return;
 jclass type=(*env)->GetObjectClass(env,reader);
 jmethodID init=(*env)->GetMethodID(env,type,"initializeImageReader","(IIIIJII)V");
 if(!init) return;
 jmethodID close=(*env)->GetMethodID(env,type,"close","()V");
 if(!close) return;
 jfieldID format=(*env)->GetFieldID(env,type,"mHardwareBufferFormat","I");
 if(!format) return;
 jfieldID space=(*env)->GetFieldID(env,type,"mDataSpace","I");
 if(!space) return;
 (*env)->CallVoidMethod(env,reader,close);
 if((*env)->ExceptionCheck(env)) return;
 (*env)->SetIntField(env,reader,format,324);
 (*env)->SetIntField(env,reader,space,146931712);
 (*env)->CallVoidMethod(env,reader,init,4096,3072,34,3,(jlong)1048579,324,146931712);
}
/* Initialize on an attached native thread, without restarting the app under
 * instrumentation. Android's JNI lookup uses this thread's native call context.
 * No global hidden-API policy or camera metadata is changed. */
typedef unsigned long jc_pthread_t;
extern int pthread_create(jc_pthread_t *, const void *, void *(*)(void *), void *);
extern int pthread_join(jc_pthread_t, void **);
typedef struct { JavaVM *vm; jobject reader; jthrowable error; int attached; } JcReaderInit;
static void *jc_init_reader_thread(void *arg) {
 JcReaderInit *job=(JcReaderInit *)arg; JNIEnv *env=0;
 if((*job->vm)->AttachCurrentThread(job->vm,&env,0)!=JNI_OK) return 0;
 job->attached=1;
 Java_local_jc_mainraw_NativeRawSurfaceProbe_reinitialize(env,0,job->reader);
 if((*env)->ExceptionCheck(env)) {
   jthrowable error=(*env)->ExceptionOccurred(env); (*env)->ExceptionClear(env);
   job->error=(jthrowable)(*env)->NewGlobalRef(env,error);
 }
 (*job->vm)->DetachCurrentThread(job->vm); return 0;
}
JNIEXPORT void JNICALL Java_local_jc_mainraw_NativeRawSurfaceProbe_reinitializeAttached(
 JNIEnv *env,jclass cls,jobject reader) {
 (void)cls; JcReaderInit job={0}; jc_pthread_t thread;
 if((*env)->GetJavaVM(env,&job.vm)!=JNI_OK) return;
 job.reader=(*env)->NewGlobalRef(env,reader); if(!job.reader) return;
 int created=pthread_create(&thread,0,jc_init_reader_thread,&job);
 if(created==0) pthread_join(thread,0);
 (*env)->DeleteGlobalRef(env,job.reader);
 if(job.error) { (*env)->Throw(env,job.error); (*env)->DeleteGlobalRef(env,job.error); }
 else if(created!=0 || !job.attached) {
   jclass error=(*env)->FindClass(env,"java/lang/IllegalStateException");
   if(error) (*env)->ThrowNew(env,error,"Native RAW initialization thread unavailable");
 }
}
JNIEXPORT jintArray JNICALL Java_local_jc_mainraw_NativeRawSurfaceProbe_configure(
 JNIEnv *env,jclass cls,jobject surface,jint w,jint h,jint format,jint dataspace) {
 (void)cls; jint values[3]={-1,-1,-1};
 if(w!=4096 || h!=3072 || format!=324 || dataspace!=146931712) return 0;
 ANativeWindow *window=ANativeWindow_fromSurface(env,surface);
 if(window) {
   values[0]=ANativeWindow_setBuffersGeometry(window,w,h,format);
   if(values[0]==0) values[1]=ANativeWindow_setBuffersDataSpace(window,dataspace);
   values[2]=ANativeWindow_getFormat(window);
   ANativeWindow_release(window);
 }
 jintArray out=(*env)->NewIntArray(env,3);
 if(out) (*env)->SetIntArrayRegion(env,out,0,3,values);
 return out;
}
JNIEXPORT jlongArray JNICALL Java_local_jc_mainraw_NativeRawSurfaceProbe_describe(
 JNIEnv *env,jclass cls,jobject hardware) {
 (void)cls; if(!hardware) return 0;
 AHardwareBuffer *buffer=AHardwareBuffer_fromHardwareBuffer(env,hardware);
 if(!buffer) return 0;
 AHardwareBuffer_Desc d; AHardwareBuffer_describe(buffer,&d);
 jlong values[6]={d.width,d.height,d.layers,d.format,d.stride,(jlong)d.usage};
 jlongArray out=(*env)->NewLongArray(env,6);
 if(out) (*env)->SetLongArrayRegion(env,out,0,6,values);
 return out;
}
