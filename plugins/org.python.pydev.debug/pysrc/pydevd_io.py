try:
    __setFalse = False
except:
    import __builtin__
    setattr(__builtin__, 'True', 1)
    setattr(__builtin__, 'False', 0)


class IORedirector:
    '''This class works to redirect the write function to many streams
    '''
    
    def __init__(self, *args):
        self._redirectTo = args
        
    def write(self, s):
        for r in self._redirectTo:
            try:
                r.write(s)
            except:
                pass
            
    def flush(self):
        for r in self._redirectTo:
            r.flush()

    
class IOBuf:
    '''This class works as a replacement for stdio and stderr.
    It is a buffer and when its contents are requested, it will erase what
    
    it has so far so that the next return will not return the same contents again.
    '''
    def __init__(self):
        self.buflist = []
    
    def getvalue(self):
        b = self.buflist
        self.buflist = [] #clear it
        return ''.join(b)
    
    def write(self, s):
        self.buflist.append(s)
        
    def flush(self):
        pass
    
    
class _RedirectionsHolder:
    _stack_stdout = []
    _stack_stderr = []
    
    
def StartRedirect(keep_original_redirection=False, std='stdout'):
    '''
    @param std: 'stdout', 'stderr', or 'both'
    '''
    import sys
    buf = IOBuf()
    
    if std == 'both':
        config_stds = ['stdout', 'stderr']
    else:
        config_stds = [std]
    
    for std in config_stds:
        original = getattr(sys, std)
        stack = getattr(_RedirectionsHolder, '_stack_%s' % std)
        stack.append(original)
        
        if keep_original_redirection:
            setattr(sys, std, IORedirector(buf, getattr(sys, std)))
        else:
            setattr(sys, std, buf)
    return buf


def EndRedirect(std='stdout'):
    import sys
    if std == 'both':
        config_stds = ['stdout', 'stderr']
    else:
        config_stds = [std]
    for std in config_stds:
        stack = getattr(_RedirectionsHolder, '_stack_%s' % std)
        setattr(sys, std, stack.pop())
    
    