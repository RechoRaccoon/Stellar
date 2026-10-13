import json, os, sys
W='/tmp/claude-0/i18n/'
src=[l.rstrip('\n').replace('\\n','\n').replace('\\\\','\\') for l in open(W+'source.txt', encoding='utf8')]
out='/home/user/Stellar/shared/src/commonMain/composeResources/files/i18n'
os.makedirs(out, exist_ok=True)
for f in sorted(os.listdir(W+'work')):
    code=f[:-4]
    table={}
    for n,line in enumerate(open(W+'work/'+f, encoding='utf8')):
        line=line.rstrip('\n')
        if not line.strip(): continue
        i,_,t=line.partition('\t')
        i=int(i)
        key=src[i]
        t=t.replace('\\n','\n')
        # Placeholders must survive translation.
        import re
        if sorted(re.findall(r'\{\d+\}',key))!=sorted(re.findall(r'\{\d+\}',t)) and not key.count('{1}') and re.findall(r'\{\d+\}',key):
            print('placeholder mismatch',code,i,repr(key),repr(t)); 
        if key.count('**')!=t.count('**'): print('bold mismatch',code,i,repr(t))
        table[key]=t
    json.dump(dict(sorted(table.items())), open(out+'/'+code+'.json','w',encoding='utf8'), ensure_ascii=False, indent=0)
    print(code, len(table))
