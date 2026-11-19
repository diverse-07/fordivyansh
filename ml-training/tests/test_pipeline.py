import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from types import SimpleNamespace
from unittest.mock import patch
import numpy as np

ROOT=Path(__file__).resolve().parents[1]
def load(name):
    spec=importlib.util.spec_from_file_location(name,ROOT/(name+".py"))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module
class PipelineTest(unittest.TestCase):
    def test_different_overlapping_classes_survive_nms(self):
        module=load("verify_tflite")
        output=np.zeros((1,7,2100),dtype=np.float32)
        output[0,:4,0]=[100,100,50,50];output[0,4,0]=.9
        output[0,:4,1]=[100,100,50,50];output[0,5,1]=.8
        boxes,scores,classes=module.decode_yolov8_output(output)
        self.assertEqual(set(classes),{0,1});self.assertEqual(len(boxes),2)
    def test_cpu_training_does_not_assume_cuda_environment_variable(self):
        module=load("train_and_export");captured={}
        class Model:
            def __init__(self,*a):pass
            def train(self,**kwargs):
                captured.update(kwargs)
                path=Path("runs_vision_assist/pothole_detector/weights/best.pt")
                path.parent.mkdir(parents=True);path.write_bytes(b"fixture")
        with tempfile.TemporaryDirectory() as tmp:
            old=Path.cwd()
            try:
                os.chdir(tmp)
                with patch.dict(sys.modules,{"torch":SimpleNamespace(cuda=SimpleNamespace(is_available=lambda:False)),
                    "ultralytics":SimpleNamespace(YOLO=Model)}):
                    module.train_model("data.yaml")
            finally:os.chdir(old)
        self.assertEqual(captured["device"],"cpu")
    def test_labels_follow_trained_model_order(self):
        module=load("train_and_export")
        with tempfile.TemporaryDirectory() as tmp:
            with patch.dict(sys.modules,{"ultralytics":SimpleNamespace(YOLO=lambda _:SimpleNamespace(names={0:"manhole",1:"crack"}))}):
                module.generate_labels_file(Path(tmp),Path("fixture.pt"))
            self.assertEqual((Path(tmp)/"labels.txt").read_text().splitlines(),["manhole","crack"])
