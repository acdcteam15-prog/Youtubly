import numpy as np, tensorflow as tf
from tensorflow.keras import layers as L
R = tf.train.load_checkpoint('/home/claude/sp/model')
def w(n): return R.get_tensor(n)
def nm(base,i): return base if i==0 else f"{base}_{i}"

def unet(x, net):  # net 0 = vocals, 1 = accompaniment
    c0, b0, t0 = 7*net, 12*net, 6*net
    ci, bi, ti = [c0], [b0], [t0]
    def conv(h, f, k=5, **kw):
        n=nm('conv2d',ci[0]); ci[0]+=1
        lay=L.Conv2D(f,k,strides=kw.get('s',2),padding='same',dilation_rate=kw.get('d',1),activation=kw.get('act'))
        o=lay(h); lay.set_weights([w(n+'/kernel'),w(n+'/bias')]); return o
    def deconv(h,f):
        n=nm('conv2d_transpose',ti[0]); ti[0]+=1
        lay=L.Conv2DTranspose(f,5,strides=2,padding='same'); o=lay(h)
        lay.set_weights([w(n+'/kernel'),w(n+'/bias')]); return o
    def bn(h):
        n=nm('batch_normalization',bi[0]); bi[0]+=1
        lay=L.BatchNormalization(axis=-1,epsilon=1e-3); o=lay(h)
        lay.set_weights([w(n+'/gamma'),w(n+'/beta'),w(n+'/moving_mean'),w(n+'/moving_variance')]); return o
    lr=lambda h: L.LeakyReLU(0.2)(h)
    convs=[]; h=x
    for i,f in enumerate([16,32,64,128,256,512]):
        c=conv(h,f); convs.append(c); b=bn(c)
        h=lr(b)
    u=convs[5]
    for i,f in enumerate([256,128,64,32,16]):
        u=deconv(u,f); u=L.ReLU()(u); u=bn(u)
        u=L.Concatenate(-1)([convs[4-i],u])
    u=deconv(u,1); u=L.ReLU()(u); u=bn(u)
    m=conv(u,2,k=4,s=1,d=2,act='sigmoid')
    return m  # sigmoid mask

def build(T, variant):
    x=L.Input((T,1024,2),batch_size=1,name='magnitude')
    mv=unet(x,0)
    if variant=='light':
        out=mv
    else:
        ma=unet(x,1)
        v=L.Multiply()([mv,x]); a=L.Multiply()([ma,x])
        v2=v*v; a2=a*a
        out=(v2+5e-11)/(v2+a2+1e-10)
    out=L.Activation('linear',name='vocal_mask')(out)
    return tf.keras.Model(x,out)
