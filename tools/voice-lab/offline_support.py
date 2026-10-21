"""Keep ModelScope downloads local too; Hugging Face's offline flags do not cover it."""
def use_local_modelscope_cache():
    import modelscope
    original=modelscope.snapshot_download
    def local_snapshot(*args,**kwargs):
        kwargs['local_files_only']=True
        return original(*args,**kwargs)
    modelscope.snapshot_download=local_snapshot
