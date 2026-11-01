import numpy as np, tensorflow as tf, time, sys
sys.path.insert(0,'.'); from unet import build
T=192
plan={'light':('light','int8'),'balanced':('best','int8'),'best':('best','fp16')}
for name,(v,q) in plan.items():
    m=build(T,v)
    f=tf.function(lambda x: {'vocal_mask': m(x)}).get_concrete_function(tf.TensorSpec((1,T,1024,2),tf.float32,name='magnitude'))
    c=tf.lite.TFLiteConverter.from_concrete_functions([f],m)
    c.optimizations=[tf.lite.Optimize.DEFAULT]
    if q=='fp16': c.target_spec.supported_types=[tf.float16]
    b=c.convert(); open(f'music_{name}.tflite','wb').write(b)
    it=tf.lite.Interpreter(model_content=b,num_threads=1); it.allocate_tensors()
    i=it.get_input_details()[0]; o=it.get_output_details()[0]
    x=(np.abs(np.random.randn(1,T,1024,2))*np.linspace(2,0.01,1024)[None,None,:,None]).astype('f4')
    it.set_tensor(i['index'],x); it.invoke(); t=time.time()
    for _ in range(2): it.set_tensor(i['index'],x); it.invoke()
    dt=(time.time()-t)/2
    err=np.abs(it.get_tensor(o['index'])-m(x).numpy()).mean()
    audio=128*1024/44100
    print(name, round(len(b)/1e6,1),'MB', list(i['shape']),list(o['shape']), f'{dt:.2f}s per {audio:.2f}s -> {audio/dt:.1f}x realtime (1 x86 core); mean err {err:.4f}',flush=True)
